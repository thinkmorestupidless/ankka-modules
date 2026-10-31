package io.eitheror.casino.wallet.publish

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.WalletEvent.*
import io.eitheror.casino.wallet.funding.{CapDraw, Forfeit, Leg, Parts, RecordUpdate}

import java.time.Instant

/** contracts/facts.md, row by row: each event becomes the family it names, keyed by the wallet. */
class MappingSuite extends munit.FunSuite with LogCapturing:

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

  test("deposit_confirmed carries the deposit's gateway and scheme, none when the wallet recorded none (spec 007)") {
    val e = DepositConfirmed("pay-1", "d", "t-dep", 10000, "dreamzpay", "t1", "dreamzpay", "card", TurnoverTerms.Default, 10000, 1, 10000, 0, t0, ok)
    val fact = Facts.moneyTransactions(wallet, e).head.fact.asInstanceOf[f.DepositConfirmed]
    assertEquals((fact.psp, fact.scheme), (Some("dreamzpay"), Some("card")))
    val none = DepositConfirmed("pay-2", "d", "t-dep2", 10000, "dreamzpay", "t1", "", "", TurnoverTerms.Default, 10000, 2, 10000, 0, t0, ok)
    val bare = Facts.moneyTransactions(wallet, none).head.fact.asInstanceOf[f.DepositConfirmed]
    assertEquals((bare.psp, bare.scheme), (None, None))
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

  // ── spec 025: the pools after and the parts on every fact ───────────────

  private def field(fact: Product, name: String): Option[Any] =
    fact.productElementNames.zip(fact.productIterator).collectFirst { case (`name`, v) => v }

  private def poolsAndParts(fact: Product): (Any, Any, Option[Any], Option[Any]) =
    (field(fact, "depositPoolAfter").get, field(fact, "convertedPoolAfter").get, field(fact, "depositPartCents"), field(fact, "convertedPartCents"))

  private val p = Parts(300, 700)

  test("every money fact carries both pools after it, and its parts where it moves real balance (spec 025 FR-013)") {
    val withParts: Vector[WalletEvent] = Vector(
      DepositConfirmed("pay-1", "d", "t-dep", 1000, "dreamzpay", "t1", "dreamzpay", "card", TurnoverTerms.Default, 0, 1, 1000, 0, t0, ok,
        parts = p, depositAfter = 6000, convertedAfter = 4000),
      BetPlaced("tx-1", "d", bet.copy(parts = Some(p)), "pragmatic", Vector.empty, Vector.empty, 0, 0, 0, 10000, 0, t0, ok, 6000, 4000),
      WinCredited("w-1", "d", "t-w", "r1", "slot-a", 1000, 1000, Vector.empty, "real", Vector("tx-1"), Vector.empty, 10000, 0, t0, ok,
        parts = p, depositAfter = 6000, convertedAfter = 4000),
      BetRolledBack("rb-1", "d", "t-rb", "r1", 1000, 1000, Vector.empty, Vector("tx-1"), Vector.empty, 10000, 0, t0, ok,
        parts = p, depositAfter = 6000, convertedAfter = 4000),
      HoldReleased("rl-1", "d", "t-rl", "w-1", 1000, "cancelled", 10000, 0, t0, ok, parts = p, depositAfter = 6000, convertedAfter = 4000),
      WithdrawalReversed("rv-1", "d", "t-rv", "w-1", 1000, 10000, 0, t0, ok, parts = p, depositAfter = 6000, convertedAfter = 4000),
      AdminAdjusted("a-1", "d", "t-a", "real", -1000, Personal("x"), "correction", "op-7", None, None, Vector.empty, 10000, 0, t0, ok,
        parts = p, depositAfter = 6000, convertedAfter = 4000),
      ProviderCredited("pc-1", "d", "t-p", 1000, "fs-9", "free spins", "st8", "real", None, Vector.empty, 10000, 0, t0, ok,
        parts = p, depositAfter = 6000, convertedAfter = 4000)
    )
    withParts.foreach { e =>
      val fact = Facts.moneyTransactions(wallet, e).head.fact.asInstanceOf[Product]
      assertEquals(poolsAndParts(fact), (Some(6000L), Some(4000L), Some(Some(300L)), Some(Some(700L))), e.toString)
    }
  }

  test("a fact whose movement leaves real balance alone carries the pools after it and no parts") {
    val loss = RoundSettledAsLoss("l-1", "d", "t-l", "r1", Vector("tx-1"), Vector.empty, Vector.empty, 10000, 0, t0, ok, 6000, 4000)
    val fact = Facts.moneyTransactions(wallet, loss).head.fact.asInstanceOf[Product]
    assertEquals(poolsAndParts(fact), (Some(6000L), Some(4000L), None, None))
  }

  test("a hold's fact carries paid and voided together as its parts and the voided pair apart; the clawback is on the forfeit") {
    val e = HoldPlaced("w-1", "d", "t-h", "w-1", 3000, Vector(Forfeit("rf-1", 100, "funds_and_winnings", false)), 400, Vector.empty, 1000, 2000,
      Vector.empty, true, Vector(update("rf-1", "g1", "FORFEITED")), 6000, 0, t0, ok,
      clawbackParts = Parts(100, 300), voidedParts = Parts(0, 1000), paidParts = Parts(2000, 0), depositAfter = 3900, convertedAfter = 2100)
    val facts = Facts.moneyTransactions(wallet, e).map(_.fact)
    val hold  = facts.head.asInstanceOf[f.HoldPlaced]
    assertEquals((hold.depositPartCents.map(_.value), hold.convertedPartCents.map(_.value)), (Some(2000L), Some(1000L)))
    assertEquals((hold.voidedDepositCents.map(_.value), hold.voidedConvertedCents.map(_.value)), (Some(0L), Some(1000L)))
    val forfeit = facts(1).asInstanceOf[f.RestrictedFundsForfeited]
    assertEquals((forfeit.depositPartCents.map(_.value), forfeit.convertedPartCents.map(_.value)), (Some(100L), Some(300L)))
  }

  test("a conversion's fact is all converted") {
    val e    = BetPlaced("tx-1", "d", bet.copy(parts = Some(p)), "pragmatic", Vector(Leg("rf-1", 500)),
      Vector(update("rf-1", "grant-1", "CONVERTED_PENDING_CAP")), 0, 0, 0, 10500, 0, t0, ok, 6000, 4500)
    val conv = Facts.moneyTransactions(wallet, e)(1).fact.asInstanceOf[f.RestrictedFundsConverted]
    assertEquals((conv.depositPartCents.map(_.value), conv.convertedPartCents.map(_.value)), (Some(0L), Some(500L)))
  }

  // ── spec 022 (contracts/facts.md) ─────────────────────────────────────────

  test("a reservation and its release are reservation_placed and reservation_released with the reserved balance after") {
    val placed = ReservationPlaced("o1", "d", "t-r", "o1", 600, 1000, 0, 600, t0, ok)
    assertEquals(families(placed), Vector("reservation_placed"))
    val fact = Facts.moneyTransactions(wallet, placed).head
    val p    = fact.fact.asInstanceOf[f.ReservationPlaced]
    assertEquals((p.orderId, p.amountCents.value, p.realBalanceAfter.value, p.reservedBalanceAfter.value, p.key, fact.dedupeId),
      ("o1", 600L, 1000L, 600L, "p1/EUR", "t-r"))
    val released = ReservationReleased("o1/1", "d", "t-rl", "o1", 200, 1000, 0, 400, t0, ok)
    assertEquals(families(released), Vector("reservation_released"))
    assertEquals(Facts.moneyTransactions(wallet, released).head.fact.asInstanceOf[f.ReservationReleased].reservedBalanceAfter.value, 400L)
  }

  test("a settlement's two sides are settlement_debit, with what was released and the seller, and settlement_credit") {
    val debit = SettlementDebited("t1", "d", "t-sd", "t1", "o1", 600, 50, "bob", 400, 0, 0, t0, ok)
    assertEquals(families(debit), Vector("settlement_debit"))
    val d = Facts.moneyTransactions(wallet, debit).head.fact.asInstanceOf[f.SettlementDebit]
    assertEquals((d.tradeId, d.orderId, d.amountCents.value, d.releasedCents.value, d.counterpartyPlayerId, d.reservedBalanceAfter.value),
      ("t1", "o1", 600L, 50L, "bob", 0L))
    val credit = SettlementCredited("t1", "d", "t-sc", "t1", 600, "ada", 600, 0, 0, t0, ok)
    assertEquals(families(credit), Vector("settlement_credit"))
    assertEquals(Facts.moneyTransactions(wallet, credit).head.fact.asInstanceOf[f.SettlementCredit].counterpartyPlayerId, "ada")
  }

  test("a move to an account is account_debit; from one, resolution_payment for a resolution payment and account_credit otherwise") {
    val split = MovedToAccount("sp-1", "d", "t-sp", "pool", "m1", Purpose.Split, "sp-1", 100, 900, 0, 0, t0, ok)
    assertEquals(families(split), Vector("account_debit"))
    val s = Facts.moneyTransactions(wallet, split).head.fact.asInstanceOf[f.AccountDebit]
    assertEquals((s.accountKind, s.accountName, s.purpose, s.reference, s.amountCents.value), ("pool", "m1", "split", "sp-1", 100L))
    val paid = MovedFromAccount("m1/p1", "d", "t-pay", "pool", "m1", Purpose.ResolutionPayment, "p1", 300, 1300, 0, 0, t0, ok)
    assertEquals(families(paid), Vector("resolution_payment"))
    val r = Facts.moneyTransactions(wallet, paid).head.fact.asInstanceOf[f.ResolutionPayment]
    assertEquals((r.marketId, r.positionId, r.amountCents.value), ("m1", "p1", 300L))
    val merge = MovedFromAccount("mg-1", "d", "t-mg", "pool", "m1", Purpose.Merge, "mg-1", 100, 1000, 0, 0, t0, ok)
    assertEquals(families(merge), Vector("account_credit"))
    assertEquals((s.orderId, s.releasedCents), (None, None))
  }

  test("a split charged from a reservation is account_debit naming the order and what it released (FR-008a)") {
    val leg = MovedToAccount("t1/ada", "d", "t-leg", "pool", "m1", Purpose.Split, "t1/ada", 600, 400, 0, 0, t0, ok, orderId = Some("o1"),
      unused = 50)
    assertEquals(families(leg), Vector("account_debit"))
    val d = Facts.moneyTransactions(wallet, leg).head.fact.asInstanceOf[f.AccountDebit]
    assertEquals((d.purpose, d.reference, d.amountCents.value, d.orderId, d.releasedCents.map(_.value), d.reservedBalanceAfter.value),
      ("split", "t1/ada", 600L, Some("o1"), Some(50L), 0L))
    assertEquals(Facts.audit("goldvibe", wallet, leg).get.fact.asInstanceOf[f.AuditRecord].action, "charge-reservation-to-account")
  }

  test("the six ledger events are audited under their keys, and the publisher sets the transaction id as ce-id") {
    val e = ReservationPlaced("o1", "d", "t-r", "o1", 600, 1000, 0, 600, t0, ok)
    assertEquals(Facts.audit("goldvibe", wallet, e).get.fact.asInstanceOf[f.AuditRecord].action, "reserve")
    val result = ConsumerTestKit.of(MoneyTransactionsPublisher).onMessage(e, subject = wallet, sequenceNumber = 4)
    assertEquals(result.recordKeys, Vector(Some(wallet)))
    assertEquals(result.messages.head.metadata.get("ce-id"), Some("t-r"))
  }
