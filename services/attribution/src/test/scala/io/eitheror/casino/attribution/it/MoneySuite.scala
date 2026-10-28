package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/**
 * A player's money as facts (US4; FR-011, FR-014, FR-015, research R8, R9): deposits and the first deposit,
 * chargebacks, withdrawals and bonus cost, each with its amount in minor units and its reference.
 */
class MoneySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val EUR = Currency.unsafe("EUR")
  private def eur(v: Long) = MinorUnits.unsafe(v)

  private def pseudonym(player: String) =
    Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)

  /** A player bound by a fresh link, once the record holds the binding. */
  private def boundPlayer(): (String, String) =
    val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
    kit.eventually("bound")(kit.record(player).binding): Unit
    player -> pseudonym(player)

  private def factsOf(p: String): Vector[PlayerAttributionFact] =
    kit.factsOn("programme").collect { case f: PlayerAttributionFact if f.pseudonym == p => f }

  private def waitFor[A](what: String)(pf: PartialFunction[PlayerAttributionFact, A])(p: String): A =
    kit.eventually(what)(factsOf(p).collectFirst(pf))

  /** Waits a moment and answers what the facts were, for "nothing more is emitted". */
  private def settled(p: String): Vector[String] =
    Thread.sleep(1500)
    factsOf(p).map(_.factId)

  private def ceIds = kit.attributionMessages.flatMap(_.message.metadata.get("ce-id"))

  private def captured(player: String, payment: String, amount: Long, at: Instant = kit.now) =
    PaymentCaptured(payment, player, "psp", eur(amount), EUR, at)

  test("a first captured deposit emits first-deposit and deposit, a second deposit only; a redelivery nothing") {
    val (player, p) = boundPlayer()
    val pay1        = kit.fresh("pay")
    kit.publish(PaymentsPayments, captured(player, pay1, 5000))
    val first = waitFor("first-deposit") { case f: FirstDeposit => f }(p)
    val dep   = waitFor("deposit") { case f: Deposit => f }(p)
    assertEquals((first.amount.value, first.currency.code, first.reference), (5000L, "EUR", pay1))
    assertEquals((dep.amount.value, dep.currency.code, dep.reference), (5000L, "EUR", pay1))
    assert(ceIds.contains(s"deposit/$p/$pay1") && ceIds.contains(s"first-deposit/$p"))

    val pay2 = kit.fresh("pay")
    kit.publish(PaymentsPayments, captured(player, pay2, 700))
    kit.eventually("the second deposit")(factsOf(p).collectFirst { case d: Deposit if d.reference == pay2 => d }): Unit
    kit.publish(PaymentsPayments, captured(player, pay1, 5000))
    val ids = settled(p)
    assertEquals(ids.count(_.startsWith("first-deposit/")), 1)
    assertEquals(ids.count(_ == s"deposit/$p/$pay1"), 1)
    assertEquals(ids.count(_.startsWith("deposit/")), 2)
  }

  test("a dispute opened and a refund are chargebacks; a dispute closed is nothing") {
    val (player, p) = boundPlayer()
    val pay         = kit.fresh("pay")
    kit.publish(PaymentsPayments, captured(player, pay, 5000))
    kit.publish(PaymentsPayments, PaymentDisputeOpened(pay, player, "psp", eur(3000), EUR, "fraud", kit.now))
    val disputed = waitFor("the dispute") { case c: Chargeback if c.reference == pay => c }(p)
    assertEquals((disputed.amount.value, disputed.factId), (3000L, s"chargeback/$p/$pay"))

    kit.publish(PaymentsPayments, PaymentRefunded(pay, player, "psp", eur(2000), EUR, kit.now))
    val refunded = waitFor("the refund") { case c: Chargeback if c.reference == s"$pay/refund" => c }(p)
    assertEquals(refunded.amount.value, 2000L)

    kit.publish(PaymentsPayments, PaymentDisputeClosed(pay, player, "psp", "won", kit.now))
    assertEquals(settled(p).count(_.startsWith("chargeback/")), 2)
  }

  test("the four withdrawal families are their kinds under the payout id; approved and cancelled are nothing") {
    val (player, p) = boundPlayer()
    val (w1, w2, w3, w4) = (kit.fresh("po"), kit.fresh("po"), kit.fresh("po"), kit.fresh("po"))
    kit.publish(CasinoWithdrawals, WithdrawalRequested(player, w1, eur(1000), EUR, "web", kit.now))
    kit.publish(CasinoWithdrawals, WithdrawalApproved(player, w1, eur(1000), EUR, "op-1", kit.now))
    kit.publish(CasinoWithdrawals, WithdrawalCompleted(player, w1, eur(1000), EUR, kit.now))
    kit.publish(CasinoWithdrawals, WithdrawalFailed(player, w2, eur(200), EUR, "bank", kit.now))
    kit.publish(CasinoWithdrawals, WithdrawalReversed(player, w3, eur(300), EUR, "returned", kit.now))
    kit.publish(CasinoWithdrawals, WithdrawalCancelled(player, w4, eur(400), EUR, "player", kit.now))
    val req  = waitFor("requested") { case f: AttributedWithdrawalRequested => f }(p)
    val done = waitFor("completed") { case f: AttributedWithdrawalCompleted => f }(p)
    val fail = waitFor("failed") { case f: AttributedWithdrawalFailed => f }(p)
    val rev  = waitFor("reversed") { case f: AttributedWithdrawalReversed => f }(p)
    assertEquals(Vector(req.reference -> req.amount.value, done.reference -> done.amount.value, fail.reference -> fail.amount.value,
      rev.reference -> rev.amount.value),
      Vector(w1 -> 1000L, w1 -> 1000L, w2 -> 200L, w3 -> 300L))
    assertEquals(req.factId, s"withdrawal-requested/$p/$w1")
    assert(!settled(p).exists(_.contains(w4)))
  }

  test("a restricted award and a provider credit landing on restricted are bonus cost; forfeited and converted are nothing") {
    val (player, p) = boundPlayer()
    val wallet      = WalletScript(kit, player)
    val award       = kit.fresh("award")
    wallet.deposit(5000)
    wallet.award(award, 2000)
    val cost = waitFor("the bonus cost") { case b: BonusCost if b.reference == award => b }(p)
    assertEquals((cost.amount.value, cost.currency.code), (2000L, "EUR"))
    wallet.convert(award, 500)
    wallet.forfeit(award, 1500)
    val ref = kit.fresh("free-spins")
    wallet.providerCredit(ref, 300, "restricted")
    wallet.providerCredit(kit.fresh("cash-drop"), 100, "real")
    waitFor("the provider's bonus") { case b: BonusCost if b.reference == ref => b }(p): Unit
    assertEquals(settled(p).count(_.startsWith("bonus-cost/")), 2)
  }

  test("a deposit captured before the registration is emitted at the binding, with the first deposit") {
    val player = kit.fresh("p")
    val pay    = kit.fresh("pay")
    val at     = kit.now
    kit.publish(PaymentsPayments, captured(player, pay, 2500, at))
    kit.eventually("pending")(Option.when(kit.record(player).pending.nonEmpty)(()))
    kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))), player = player, at = at)
    val p = pseudonym(player)
    waitFor("the registration") { case r: Registration => r }(p): Unit
    assertEquals(waitFor("the first deposit") { case f: FirstDeposit => f.reference }(p), pay)
    assertEquals(waitFor("the deposit") { case d: Deposit => d.reference }(p), pay)
    assertEquals(kit.record(player).pending, Vector.empty)
  }

  test("a code bound 3 days after an organic registration does not report the deposit made in between (research R8)") {
    val player = kit.bindPlayer(None, at = kit.now.minus(JDuration.ofDays(3)))
    kit.eventually("registered")(kit.record(player).registeredAt): Unit
    val pay = kit.fresh("pay")
    kit.publish(PaymentsPayments, captured(player, pay, 4000, kit.now.minus(JDuration.ofDays(1))))
    kit.eventually("the first deposit set")(kit.record(player).firstDepositAt): Unit

    val code = kit.fresh("CODE").toUpperCase
    kit.network.codes = Map(code -> ("offer" -> kit.network.mint("aff-2", kit.fresh("lnk"))))
    kit.network.codesVersion += 1
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit
    kit.publish(CasinoRewards, BonusCodeEntered(player, code, kit.now))
    val p = pseudonym(player)
    waitFor("the registration at the code") { case r: Registration => r }(p): Unit
    val ids = settled(p)
    assert(!ids.exists(id => id.startsWith("deposit/") || id.startsWith("first-deposit/")), ids.toString)
  }

  test("an unbound player's deposits emit nothing and still set the first deposit") {
    val player = kit.bindPlayer(None)
    kit.eventually("registered")(kit.record(player).registeredAt): Unit
    val pay = kit.fresh("pay")
    kit.publish(PaymentsPayments, captured(player, pay, 1000))
    assertEquals(kit.eventually("the first deposit set")(kit.record(player).firstDeposit), s"payment.captured/$pay")
    Thread.sleep(1000)
    assert(!kit.attributionFacts.exists {
      case d: Deposit      => d.reference == pay
      case d: FirstDeposit => d.reference == pay
      case _               => false
    })
  }

  test("SC-002: a player bound by a code at registration has deposits and withdrawals attributed") {
    val code = kit.fresh("CODE").toUpperCase
    kit.network.codes = Map(code -> ("offer" -> kit.network.mint("aff-3", kit.fresh("lnk"))))
    kit.network.codesVersion += 1
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit
    val player = kit.bindPlayer(None, bonusCode = Some(code))
    assertEquals(kit.eventually("bound")(kit.record(player).binding).affiliate, "aff-3")
    val p   = pseudonym(player)
    val pay = kit.fresh("pay")
    val po  = kit.fresh("po")
    kit.publish(PaymentsPayments, captured(player, pay, 3000))
    kit.publish(CasinoWithdrawals, WithdrawalRequested(player, po, eur(1000), EUR, "web", kit.now))
    assertEquals(waitFor("the deposit") { case d: Deposit => d.affiliate }(p), "aff-3")
    assertEquals(waitFor("the withdrawal") { case w: AttributedWithdrawalRequested => w.affiliate }(p), "aff-3")
  }
