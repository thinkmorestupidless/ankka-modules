package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{Recorded, ReportingService, Settings}
import io.eitheror.casino.reporting.Facts.*

import java.time.ZoneId

/** T024: `RoundCheck`'s four statuses, a redelivered leg, the round's day; `rounds-facts` and `round-legs`. */
class RoundCheckSuite extends munit.FunSuite:
  private def kit = KeyValueEntityTestKit.of(RoundCheck, RoundCheck.id(1, "p-1", "EUR", "r-1"))

  private def figures(stake: Long, win: Long, outcome: String) =
    ProviderFigures("st8", "pr-1", "g-1", Some("Book of Gold"), Some("slots"), "gs-1", stake, win, outcome, at)
  private def settled(stake: Long, win: Long, outcome: String = "win", day: String = "2026-11-08") =
    ProviderSettled("p-1", "EUR", "r-1", figures(stake, win, outcome), day)
  private def leg(id: String, family: String, amount: Long, day: String = "2026-11-08") =
    WalletLeg(id, "p-1", "EUR", "r-1", family, amount, day)

  test("the provider and the wallet agreeing is matched; disagreeing differs") {
    val k = kit
    k.call(RoundCheck.wallet)(leg("l1", "bet_placed", 100)): Unit
    k.call(RoundCheck.wallet)(leg("l2", "win_credited", 250)): Unit
    k.call(RoundCheck.provider)(settled(100, 250)): Unit
    assertEquals(k.currentState.status, RoundChecks.Matched)
    k.call(RoundCheck.provider)(settled(100, 300)): Unit
    assertEquals(k.currentState.status, RoundChecks.Differs)
  }

  test("one side only: provider_only, wallet_only") {
    val p = kit
    p.call(RoundCheck.provider)(settled(100, 0, "loss")): Unit
    assertEquals(p.currentState.status, RoundChecks.ProviderOnly)
    val w = kit
    w.call(RoundCheck.wallet)(leg("l1", "bet_placed", 100)): Unit
    assertEquals(w.currentState.status, RoundChecks.WalletOnly)
  }

  test("a rolled-back round matches the provider's cancellation") {
    val k = kit
    k.call(RoundCheck.wallet)(leg("l1", "bet_placed", 100)): Unit
    k.call(RoundCheck.wallet)(leg("l2", "bet_rolled_back", 100)): Unit
    k.call(RoundCheck.provider)(settled(100, 0, "cancelled")): Unit
    assertEquals(k.currentState.status, RoundChecks.Matched)
  }

  test("a redelivered leg is not added twice; a loss marks the wallet's side with nothing") {
    val k = kit
    k.call(RoundCheck.wallet)(leg("l1", "bet_placed", 100)): Unit
    assert(!k.call(RoundCheck.wallet)(leg("l1", "bet_placed", 100)).replyValue)
    k.call(RoundCheck.wallet)(leg("l2", "round_settled_as_loss", 0)): Unit
    assertEquals(k.currentState.wallet, Some(WalletFigures(100, 0, 0)))
  }

  test("the round's day is the wallet's round day once a leg is seen, else the provider's") {
    val k = kit
    k.call(RoundCheck.provider)(settled(100, 0, "loss", day = "2026-11-09")): Unit
    assertEquals(k.currentState.day, "2026-11-09")
    k.call(RoundCheck.wallet)(leg("l1", "bet_placed", 100, day = "2026-11-08")): Unit
    assertEquals(k.currentState.day, "2026-11-08")
    k.call(RoundCheck.provider)(settled(100, 0, "loss", day = "2026-11-09")): Unit
    assertEquals(k.currentState.day, "2026-11-08")
  }

  test("rounds-facts sends a settlement to its round with its day in the zone") {
    val r   = Recorded()
    val zoned = Settings("goldvibe", reportingZone = ZoneId.of("Africa/Johannesburg"), modules = Set("casino"))
    val kit = ConsumerTestKit.of(RoundsFacts(zoned), client = r.client)
    val fact = f.RoundSettled("p-1", eur, "r-1", "st8", "pr-1", "g-1", "gs-1", m(100), m(250), s(150), "win", Vector("t1"),
      Vector("pt1"), Vector("t1"), None, java.time.Instant.parse("2026-11-08T22:30:00Z"), Some("Book of Gold"), Some("slots"))
    assertEquals(kit.onMessage(Some(fact)).effect, ConsumerEffect.Done)
    val call = r.calls.head
    assertEquals((call.component, call.entity, call.method), ("round-check", "g1/p-1/EUR/r-1", "provider"))
    val sent = call.as[ProviderSettled](using RoundCheck.given_Serializer_ProviderSettled)
    assertEquals((sent.day, sent.figures.gameName, sent.figures.session), ("2026-11-09", Some("Book of Gold"), "gs-1"))
  }

  test("rounds-facts passes over an opened round: only a settlement is checked") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(RoundsFacts(Settings("goldvibe", modules = Set("casino"))), client = r.client)
    val opened = f.RoundOpened("p-1", eur, "r-1", "st8", "pr-1", "g-1", "gs-1", None, java.time.Instant.parse("2026-11-08T22:00:00Z"))
    assertEquals(kit.onMessage(Some(opened)).effect, ConsumerEffect.Ignore)
    assert(r.calls.isEmpty, r.calls.toString)
  }

  test("round-legs sends the casino round legs of this generation's books, on the round's day") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(RoundLegs(Settings("goldvibe")), client = r.client)
    val moved = Moved("f1", "win_credited", "win", 250, 250, Some("r-1"), "r-1", "2026-11-09", Some("2026-11-08"), at, casino = true,
      None, "p-1", "EUR", "t1", 0, 0)
    assertEquals(kit.onMessage(moved, subject = "g1/p-1/EUR").effect, ConsumerEffect.Done)
    assertEquals(r.calls.map(_.as[WalletLeg](using RoundCheck.given_Serializer_WalletLeg)), Vector(WalletLeg("f1", "p-1", "EUR", "r-1", "win_credited", 250, "2026-11-08", Some(at))))
    assertEquals(kit.onMessage(moved, subject = "g2/p-1/EUR").effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(moved.copy(family = "deposit_confirmed", roundId = None), subject = "g1/p-1/EUR").effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(RecordsChanged("f1", Vector.empty), subject = "g1/p-1/EUR").effect, ConsumerEffect.Ignore)
    assertEquals(r.calls.length, 1)
  }

  test("rounds-facts only with casino; round-legs always") {
    def ids(s: Settings) = ReportingService.descriptors(s).map(_.componentId.toString)
    assert(ids(Settings("goldvibe", modules = Set("casino"))).contains("rounds-facts-g1"))
    assert(!ids(Settings("goldvibe")).contains("rounds-facts-g1"))
    assert(ids(Settings("goldvibe")).contains("round-legs-g1"))
  }
