package io.eitheror.casino.wallet.publish

import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.WalletEvent.*
import io.eitheror.casino.wallet.funding.{CapDraw, Forfeit, Leg, RecordUpdate}

import java.time.Instant

/** contracts/facts.md, row by row: each event becomes the family it names, keyed by the wallet. */
class MappingSuite extends munit.FunSuite:

  private val t0     = Instant.parse("2026-10-09T10:00:00Z")
  private val wallet = "p1/EUR"
  private val ok     = Outcome()

  private def update(id: String, grant: String, status: String = "ACTIVE") =
    RecordUpdate(id, 0, 0, 0, 0, 0, 0, 0, status, closedNow = false, grant)

  private def families(e: WalletEvent): Vector[String] =
    Facts.moneyTransactions(wallet, e).map(p => f.MoneyTransactions.families.find(_.sample.getClass == p.fact.getClass).fold("?")(_.name))

  private val bet = Bet("tx-1", "r1", "t-bet", 1000, 1000, Vector.empty, Vector.empty, "real", BetStatus.Open, "s1", "slot-a", t0)

  test("a deposit is deposit_confirmed with its ordinal and payment reference, and payment.captured keyed by the payment") {
    val e = DepositConfirmed("pay-1", "d", "t-dep", 10000, "dreamzpay", "t1", "dreamzpay", "card", TurnoverTerms.Default, 10000, 1, 10000, 0, t0, ok)
    val fact = Facts.moneyTransactions(wallet, e).head.fact.asInstanceOf[f.DepositConfirmed]
    assertEquals((fact.depositOrdinal, fact.paymentReference, fact.realBalanceAfter.value, fact.key), (1, "pay-1", 10000L, "p1/EUR"))
    val captured = Facts.paymentCaptured(wallet, e).get.fact
    assertEquals((captured.key, captured.paymentProvider), ("pay-1", "dreamzpay"))
  }

  test("a bet that converts a record is bet_placed and one restricted_funds_converted naming the award") {
    val e = BetPlaced("tx-1", "d", bet, "pragmatic", Vector(Leg("rf-1", 500)), Vector(update("rf-1", "grant-1", "CONVERTED_PENDING_CAP")), 0, 0, 0, 9500, 0, t0, ok)
    assertEquals(families(e), Vector("bet_placed", "restricted_funds_converted"))
    val converted = Facts.moneyTransactions(wallet, e)(1)
    assertEquals((converted.fact.asInstanceOf[f.RestrictedFundsConverted].awardId, converted.dedupeId), ("grant-1", "t-bet/rf-1"))
  }

  test("a cancel puts the clawback on the first record's forfeit, and on a forfeit of its own when no record was open") {
    val withRecord = RestrictedFundsCancelled("c-1", "d", "t-c", Vector(Forfeit("rf-1", 2000, "", false)), 300, 2000, Vector(update("rf-1", "grant-1")), 0, 0, t0, ok)
    val fact = Facts.moneyTransactions(wallet, withRecord).head.fact.asInstanceOf[f.RestrictedFundsForfeited]
    assertEquals((fact.clawbackCents.value, fact.amountCents.value, fact.reason), (300L, 2000L, "cancel"))
    val noRecord = RestrictedFundsCancelled("c-2", "d", "t-c2", Vector.empty, 300, 0, Vector.empty, 0, 0, t0, ok)
    assertEquals(families(noRecord), Vector("restricted_funds_forfeited"))
  }

  test("a hold is hold_placed with the voided amount, then one forfeit per record with the clawback once") {
    val e = HoldPlaced("w-1", "d", "t-h", "w-1", 5000, Vector(Forfeit("rf-1", 100, "funds_and_winnings", false), Forfeit("rf-2", 200, "funds", false)),
      400, Vector(CapDraw("rf-3", 0, 100, closes = true)), 900, 4100, Vector.empty, true,
      Vector(update("rf-1", "g1", "FORFEITED"), update("rf-2", "g2", "FORFEITED")), 1000, 0, t0, ok)
    assertEquals(families(e), Vector("hold_placed", "restricted_funds_forfeited", "restricted_funds_forfeited"))
    val facts = Facts.moneyTransactions(wallet, e).map(_.fact)
    assertEquals(facts.head.asInstanceOf[f.HoldPlaced].voidedCents.value, 900L)
    assertEquals(facts.tail.map(_.asInstanceOf[f.RestrictedFundsForfeited].clawbackCents.value), Vector(400L, 0L))
  }

  test("an adjustment's fact carries the category, never the operator's text (FR-033)") {
    val e = AdminAdjusted("a-1", "d", "t-a", "real", 500, Personal("goodwill after the outage on 3 Oct, per J. Smith"), "goodwill", "op-7", None, None, Vector.empty, 500, 0, t0, ok)
    val fact = Facts.moneyTransactions(wallet, e).head.fact.asInstanceOf[f.AdminAdjusted]
    assertEquals((fact.reason, fact.actorId, fact.amount.value), ("goodwill", "op-7", 500L))
    assert(!Facts.audit("goldvibe", wallet, e).get.fact.toString.contains("Smith"))
  }

  test("a reversal is withdrawal_reversed and a provider credit is provider_credited naming where it landed") {
    assertEquals(families(WithdrawalReversed("rv-1", "d", "t-r", "w-1", 5000, 5000, 0, t0, ok)), Vector("withdrawal_reversed"))
    val credit = ProviderCredited("pc-1", "d", "t-p", 700, "fs-9", "free spins", "st8", "restricted", Some("rf-1"), Vector(update("rf-1", "grant-1")), 0, 700, t0, ok)
    val fact = Facts.moneyTransactions(wallet, credit).head.fact.asInstanceOf[f.ProviderCredited]
    assertEquals((fact.landedOn, fact.awardId), ("restricted", Some("grant-1")))
  }

  test("events that move no money publish no money fact, and every refusal is audited") {
    val refused = CommandRefused("tx-9", "d", "place-bet", "INSUFFICIENT_FUNDS", "Insufficient funds in real and bonus wallets", Some(1000), t0, ok)
    assertEquals(Facts.moneyTransactions(wallet, refused), Vector.empty)
    assertEquals(Facts.moneyTransactions(wallet, HoldConfirmed("c-1", "d", "w-1", t0, ok)), Vector.empty)
    val audit = Facts.audit("goldvibe", wallet, refused).get.fact.asInstanceOf[f.AuditRecord]
    assertEquals((audit.outcome, audit.action, audit.subjectId, audit.brand), ("INSUFFICIENT_FUNDS", "place-bet", "p1", "goldvibe"))
  }

  test("the publisher keys every fact by the wallet and sets the dedupe id as ce-id") {
    val e      = BetPlaced("tx-1", "d", bet, "pragmatic", Vector.empty, Vector.empty, 0, 1000, 0, 9000, 0, t0, ok)
    val result = ConsumerTestKit.of(MoneyTransactionsPublisher).onMessage(e, subject = wallet, sequenceNumber = 3)
    assertEquals(result.recordKeys, Vector(Some(wallet)))
    assertEquals(result.messages.head.metadata.get("ce-id"), Some("t-bet"))
  }

  test("payment outcomes become their families, keyed by the payment") {
    import io.eitheror.casino.wallet.payments.{PaymentEvent, PaymentOutcome}
    val failed = PaymentEvent.PaymentOutcomeRecorded("pay-1", PaymentOutcome("failed", "p1", "dreamzpay", code = Some("DECLINED"), reason = Some("card"), at = t0))
    val fact   = Facts.payments(failed).get.fact.asInstanceOf[f.PaymentFailed]
    assertEquals((fact.key, fact.code, fact.paymentProvider), ("pay-1", "DECLINED", "dreamzpay"))
  }
