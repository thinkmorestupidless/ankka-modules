package io.eitheror.casino.payouts.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.limits.Counted
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.request.RequestEvent.*

import java.time.Instant

/** data-model.md's consumers: what each fact or event becomes, a redelivery changing nothing, a new player starting a record. */
class ConsumersSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-10-15T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")

  private def registered(player: String, names: Boolean = true, email: Boolean = true) =
    PlayerRegistered(player, at, None, None, None, Some("DE"), Some("EUR"), None, at,
      givenName = Option.when(names)(Personal("Ana")), familyName = Option.when(names)(Personal("Silva")),
      email = Option.when(email)(Personal("ana@example.test")))
  private def verified(player: String) = PlayerKycVerified(player, "pending", "verified", "operator", None, None, at)
  private def deposit(psp: Option[String], scheme: Option[String]) =
    DepositConfirmed("p-1", Eur, "t-1", MinorUnits.unsafe(5000), "ref-1", 1, SignedMinorUnits(5000), MinorUnits.unsafe(0), at,
      psp = psp, scheme = scheme)

  private def apply(kit: EventSourcedTestKit[PlayerPayouts, PlayerState, PlayerEvent], c: PlayersConsumer.Command) = c match
    case c: SeeKyc    => kit.call(PlayerPayouts.seeKyc)(c).events
    case c: SeeNames  => kit.call(PlayerPayouts.seeNames)(c).events
    case c: SeeEmail  => kit.call(PlayerPayouts.seeEmail)(c).events
    case c: SeeClosed => kit.call(PlayerPayouts.seeClosed)(c).events

  test("each KYC family is its status"):
    val facts = Vector[CasinoPlayersFact](PlayerKycSubmitted("p-1", "d-1", "identity_front", at),
      PlayerKycPending("p-1", "unverified", "pending", at), verified("p-1"),
      PlayerKycRejected("p-1", "pending", "rejected", "operator", None, None, at))
    assertEquals(facts.flatMap(PlayersConsumer.commands).collect { case SeeKyc(s, _) => s },
      Vector(Kyc.Submitted, Kyc.Pending, Kyc.Verified, Kyc.Rejected))

  test("player_registered gives the names and the email; names only as a pair"):
    assertEquals(PlayersConsumer.commands(registered("p-1")),
      Vector(SeeNames(Personal("Ana"), Personal("Silva"), at), SeeEmail(Personal("ana@example.test"), at)))
    assertEquals(PlayersConsumer.commands(registered("p-1", names = false, email = false)), Vector.empty)
    val half = PlayerProfileUpdated("p-1", Vector("givenName"), Some(Personal("Ana")), None, None, None, None, None, None, None, at)
    assertEquals(PlayersConsumer.commands(half), Vector.empty)

  test("player_email_changed and player_closed"):
    assertEquals(PlayersConsumer.commands(PlayerEmailChanged("p-1", Personal("new@example.test"), at)),
      Vector(SeeEmail(Personal("new@example.test"), at)))
    assertEquals(PlayersConsumer.commands(PlayerClosed("p-1", "active", "closed", "player", None, None, at)), Vector(SeeClosed(at)))

  test("a fact for a player with no record starts one; its redelivery changes nothing"):
    val kit = EventSourcedTestKit.of(PlayerPayouts, "p-new")
    val first = (PlayersConsumer.commands(registered("p-new")) ++ PlayersConsumer.commands(verified("p-new"))).flatMap(apply(kit, _))
    assertEquals(first.size, 3)
    assertEquals((kit.currentState.known, kit.currentState.kyc, kit.currentState.email), (true, Kyc.Verified, Some(Personal("ana@example.test"))))
    val again = (PlayersConsumer.commands(registered("p-new")) ++ PlayersConsumer.commands(verified("p-new"))).flatMap(apply(kit, _))
    assertEquals(again, Vector.empty[PlayerEvent])

  test("a deposit gives its gateway and scheme; one without a gateway is nothing; its redelivery changes nothing"):
    assertEquals(DepositsConsumer.command(deposit(Some(" UnityFinance "), Some("SEPA"))), Some(SeeDeposit("UnityFinance", Some("SEPA"), at)))
    assertEquals(DepositsConsumer.command(deposit(Some("cozeni"), Some(" "))), Some(SeeDeposit("cozeni", None, at)))
    assertEquals(DepositsConsumer.command(deposit(None, Some("SEPA"))), None)
    val kit = EventSourcedTestKit.of(PlayerPayouts, "p-1")
    val c   = DepositsConsumer.command(deposit(Some("UnityFinance"), Some("SEPA"))).get
    assertEquals(kit.call(PlayerPayouts.seeDeposit)(c).events.size, 1)
    assertEquals(kit.call(PlayerPayouts.seeDeposit)(c).events, Vector.empty[PlayerEvent])
    assertEquals(kit.currentState.deposits, Map("unityfinance" -> Set("SEPA")))

  test("an opened request counts for its player; a closed-unpaid one stops counting; the others change nothing"):
    val terminal = TerminalRef("t-1", "sb-1", "Bank", "unityfinance", "EUBANKING", true)
    val opened   = Opened("r-1", "p-1", 5000L, 5000L, "EUR", terminal, Payee(), Forfeit(false, 0L, 0L), "verified", at)
    val Some(Left((player, count))) = RequestsConsumer.change(opened, "r-1"): @unchecked
    assertEquals((player, count.request), ("p-1", Counted("r-1", 5000L, Eur, at)))
    for e <- Vector[RequestEvent](Cancelled(Actor.Player, None, at), Rejected("op-1", None, at),
        Failed(Source.Gateway, "declined", None, at), Reversed("reversed", None, at)) do
      assertEquals(RequestsConsumer.change(e, "r-1"), Some(Right(Uncount("r-1", at))), e.toString)
    assertEquals(RequestsConsumer.change(Approved("op-1", at), "r-1"), None)
    val kit = EventSourcedTestKit.of(PlayerPayouts, "p-1")
    kit.call(PlayerPayouts.countRequest)(count): Unit
    assertEquals(kit.call(PlayerPayouts.countRequest)(count).events, Vector.empty[PlayerEvent])
    kit.call(PlayerPayouts.uncount)(Uncount("r-1", at)): Unit
    assertEquals(kit.call(PlayerPayouts.uncount)(Uncount("r-1", at)).events, Vector.empty[PlayerEvent])
    assertEquals(kit.currentState.recent.map(_.counts), Vector(false))

  test("another family, and a fact that cannot be read, are ignored"):
    val players = ConsumerTestKit.of(PlayersConsumer)
    assertEquals(players.onMessage(Some(PlayerLoggedIn("p-1", "s-1", at))).effect, ConsumerEffect.Ignore)
    assertEquals(players.onMessage(None).effect, ConsumerEffect.Ignore)
    val deposits = ConsumerTestKit.of(DepositsConsumer)
    assertEquals(deposits.onMessage(None).effect, ConsumerEffect.Ignore)
    assertEquals(deposits.onMessage(Some(deposit(None, None))).effect, ConsumerEffect.Ignore)
