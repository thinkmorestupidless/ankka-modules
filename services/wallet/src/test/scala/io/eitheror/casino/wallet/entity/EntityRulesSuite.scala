package io.eitheror.casino.wallet.entity

import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.wallet.entity.WalletEvent.*

import java.time.{Duration, Instant}

/** The entity's rules that the fixtures and the features do not name one by one. */
class EntityRulesSuite extends munit.FunSuite with LogCapturing:

  private val t0 = Instant.parse("2026-10-09T10:00:00Z")
  private def deposit(key: String, amount: Long, at: Instant, multiplier: Long = 0) =
    Deposit(key, amount, "psp", "t1", "psp", "card", TurnoverTerms(multiplier, "gross_wagered"), at)

  // ── deposit sums (FR-030, research R5) ──────────────────────────────────────

  test("the deposit sums are exact at the 24-hour, 7-day and 30-day boundaries, as of the time asked") {
    val kit = EventSourcedTestKit.of(WalletEntity, "sums/EUR")
    kit.call(WalletEntity.deposit)(deposit("d-31d", 1, t0.minus(Duration.ofDays(31))))
    kit.call(WalletEntity.deposit)(deposit("d-30d", 10, t0.minus(Duration.ofDays(30)).plusSeconds(1)))
    kit.call(WalletEntity.deposit)(deposit("d-7d", 100, t0.minus(Duration.ofDays(7)).plusSeconds(1)))
    kit.call(WalletEntity.deposit)(deposit("d-1h", 1000, t0.minus(Duration.ofHours(1))))
    assertEquals(kit.call(WalletEntity.depositSums)(DepositSumsAt(t0)).replyValue, DepositSums(1000, 1100, 1110))
    // A day later the newest no longer counts in the last 24 hours.
    assertEquals(kit.call(WalletEntity.depositSums)(DepositSumsAt(t0.plus(Duration.ofDays(1)))).replyValue.last24h, 0L)
  }

  test("a repeated deposit is counted once and an adjustment is never a deposit") {
    val kit = EventSourcedTestKit.of(WalletEntity, "sums2/EUR")
    kit.call(WalletEntity.deposit)(deposit("d-1", 500, t0))
    kit.call(WalletEntity.deposit)(deposit("d-1", 500, t0))
    kit.call(WalletEntity.adjust)(Adjust("a-1", "real", 900, "goodwill", "goodwill", "op", t0))
    assertEquals(kit.call(WalletEntity.depositSums)(DepositSumsAt(t0)).replyValue, DepositSums(500, 500, 500))
    assertEquals(kit.currentState.depositOrdinal, 1)
  }

  test("the key store holds the last RecentKeys keys: the oldest leaves as the next arrives, a repeat of a held key still answers (FR-002, R17)") {
    val kit = EventSourcedTestKit.of(WalletEntity, "ring/EUR")
    kit.call(WalletEntity.deposit)(Deposit("seed", 10_000_000L, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0))
    // The seed and RecentKeys − 1 bets fill the store exactly.
    (1 until WalletState.RecentKeys).foreach { i =>
      kit.call(WalletEntity.placeBet)(PlaceBet(s"b-$i", s"r-$i", "slot", "s1", "prov", "slots", 1, false, BetSwitches(), t0)): Unit
    }
    val full = kit.currentState
    assertEquals(full.seen.seen.size, WalletState.RecentKeys)
    assertEquals(full.keyOrder.head, "seed")
    assertEquals(full.keyOrder.last, s"b-${WalletState.RecentKeys - 1}")
    assert(full.seen.seen.contains("seed") && full.seen.seen.contains("b-1"))

    // The next key evicts "seed", the oldest; a repeat of "seed" is now a new command to the wallet.
    kit.call(WalletEntity.placeBet)(PlaceBet("b-next", "r-next", "slot", "s1", "prov", "slots", 1, false, BetSwitches(), t0)): Unit
    val s = kit.currentState
    assertEquals(s.seen.seen.size, WalletState.RecentKeys)
    assert(!s.seen.seen.contains("seed"), "the oldest key was kept")
    assert(s.seen.seen.contains("b-1") && s.seen.seen.contains("b-next"))
    assertEquals(s.keyOrder.size, WalletState.RecentKeys)

    // A repeat of a held key answers the first outcome and adds nothing; it does not move in the order.
    val events = kit.allEvents.size
    val again  = kit.call(WalletEntity.placeBet)(PlaceBet("b-2", "r-2", "slot", "s1", "prov", "slots", 1, false, BetSwitches(), t0)).replyValue
    assertEquals(again, s.seen.seen("b-2").outcome)
    assertEquals(kit.allEvents.size, events)
    assertEquals(kit.currentState.keyOrder, s.keyOrder)
  }

  test("deposits older than 30 days leave state as later deposits arrive, so the state stays bounded") {
    val kit = EventSourcedTestKit.of(WalletEntity, "sums3/EUR")
    (0 until 90).foreach(d => kit.call(WalletEntity.deposit)(deposit(s"d-$d", 1, t0.plus(Duration.ofDays(d.toLong)))))
    assert(kit.currentState.recentDeposits.size <= 31, kit.currentState.recentDeposits.size.toString)
    assertEquals(kit.currentState.totalDeposits, 90L)
  }

  // ── the hold preview (FR-023a) ──────────────────────────────────────────────

  test("the hold preview answers what the hold then does, and moves nothing") {
    val kit = EventSourcedTestKit.of(WalletEntity, "preview/EUR")
    kit.call(WalletEntity.deposit)(deposit("d-1", 10000, t0))
    kit.call(WalletEntity.award)(Award("g-1", "bonus", 2000, 1, 4000, 0, None, Rules(forfeitOnWithdrawal = "funds"), None, t0))
    val before  = kit.allEvents.size
    val preview = kit.call(WalletEntity.previewHold)(PreviewHold(3000)).replyValue
    assertEquals(kit.allEvents.size, before)
    val hold = kit.call(WalletEntity.hold)(Hold("w-1", 3000, playerConfirmed = true, t0)).replyValue
    assertEquals(preview.refusal, None)
    assertEquals(Some(preview.confirmationRequired), hold.confirmationRequired)
    assertEquals(Some(preview.clawback), hold.clawbackApplied)
    assertEquals(preview.forfeits.map(_.recordId), hold.forfeited)
    assertEquals(preview.totalForfeit, 2000L)
  }

  test("the hold preview names the refusal the hold would give") {
    val kit = EventSourcedTestKit.of(WalletEntity, "preview2/EUR")
    kit.call(WalletEntity.deposit)(deposit("d-1", 1000, t0, multiplier = 1))
    assertEquals(kit.call(WalletEntity.previewHold)(PreviewHold(500)).replyValue.refusal, Some("WITHDRAWAL_BLOCKED_TURNOVER"))
    assertEquals(kit.call(WalletEntity.previewHold)(PreviewHold(5000)).replyValue.refusal, Some("INSUFFICIENT_FUNDS"))
  }

  // ── personal data (FR-033) ──────────────────────────────────────────────────

  test("an adjustment's reason is held as Personal in the event, and the category is not personal") {
    val kit = EventSourcedTestKit.of(WalletEntity, "personal/EUR")
    kit.call(WalletEntity.deposit)(deposit("d-1", 1000, t0))
    val result = kit.call(WalletEntity.adjust)(Adjust("a-1", "real", -100, "refund after the outage, per ticket 4411", "correction", "op-9", t0))
    val event  = result.eventOfType[AdminAdjusted]
    assertEquals(io.eitheror.casino.contract.personal.Personal.reveal(event.reason), "refund after the outage, per ticket 4411")
    assertEquals((event.category, event.actorId), ("correction", "op-9"))
  }

  // ── stored types ────────────────────────────────────────────────────────────

  test("every event round-trips through the journal's serializer") {
    val serializer = Codecs.serializer[WalletEvent]("wallet.event")
    val kit        = EventSourcedTestKit.of(WalletEntity, "round-trip/EUR")
    kit.call(WalletEntity.deposit)(deposit("d-1", 10000, t0))
    kit.call(WalletEntity.award)(Award("g-1", "bonus", 2000, 1, 4000, 0, Some(t0.plusSeconds(86400)), Rules(maxStake = Some(500), scopeExclude = Set("game:x")), None, t0))
    kit.call(WalletEntity.placeBet)(PlaceBet("b-1", "r1", "slot", "s1", "prov", "slots", 1500, false, BetSwitches(), t0))
    kit.call(WalletEntity.creditWin)(CreditWin("win-1", "r1", 3000, t0))
    kit.call(WalletEntity.placeBet)(PlaceBet("b-2", "r2", "slot", "s1", "prov", "slots", 100, false, BetSwitches(), t0))
    kit.call(WalletEntity.rollbackBet)(RollbackBet("rb-1", "r2", None, t0))
    kit.call(WalletEntity.placeBet)(PlaceBet("b-3", "r3", "slot", "s1", "prov", "slots", 100, false, BetSwitches(), t0))
    kit.call(WalletEntity.settleLoss)(SettleLoss("l-1", "r3", t0))
    kit.call(WalletEntity.providerCredit)(ProviderCredit("pc-1", 100, "fs", "spins", "st8", t0))
    kit.call(WalletEntity.switchPrimary)(SwitchPrimary("s-1", "g-1", t0))
    kit.call(WalletEntity.adjust)(Adjust("a-1", "restricted", -10, "reason", "correction", "op", t0))
    kit.call(WalletEntity.forfeit)(ForfeitAward("f-1", None, t0))
    kit.call(WalletEntity.cancel)(CancelAward("c-1", None, t0))
    kit.call(WalletEntity.hold)(Hold("w-1", 100, true, t0))
    kit.call(WalletEntity.release)(Release("rl-1", "w-1", "cancelled", t0))
    kit.call(WalletEntity.hold)(Hold("w-2", 100, true, t0))
    kit.call(WalletEntity.confirm)(Confirm("cf-1", "w-2", t0))
    kit.call(WalletEntity.reverse)(Reverse("rv-1", "w-2", t0))
    kit.call(WalletEntity.setTurnoverOverride)(SetTurnoverOverride(Some(TurnoverTerms(2, "gross_wagered")), "op", t0))
    kit.call(WalletEntity.closePendingCap)(ClosePendingCap("cp-1", t0))
    val events = kit.allEvents
    events.foreach(e => assertEquals(serializer.fromBytes(serializer.toBytes(e)), e))
    val kinds = events.map(_.getClass.getSimpleName).toSet
    val refusals = events.collect { case e: CommandRefused => s"${e.commandType}:${e.code}" }
    assert(kinds.size >= 17, s"only $kinds were exercised; refused: $refusals")
  }

  // ── the original's refusal texts (contracts/wire.md) ────────────────────────

  test("the route-level refusals carry the original's codes and texts") {
    val kit = EventSourcedTestKit.of(WalletEntity, "texts/EUR")
    def reply(o: Outcome) = (o.code.getOrElse(""), o.message.getOrElse(""))
    assertEquals(reply(kit.call(WalletEntity.placeBet)(PlaceBet("b", "r", "g", "s", "p", "c", 1, false, BetSwitches(), t0)).replyValue),
      ("WALLET_NOT_FOUND", "Wallet not found"))
    kit.call(WalletEntity.deposit)(deposit("d-1", 100, t0))
    assertEquals(reply(kit.call(WalletEntity.creditWin)(CreditWin("w", "r9", 1, t0)).replyValue), ("ROUND_NOT_FOUND", "No open bet found for round r9"))
    assertEquals(reply(kit.call(WalletEntity.adjust)(Adjust("a", "real", -500, "x", "c", "op", t0)).replyValue),
      ("DEBIT_EXCEEDS_BALANCE", "Requested debit of 500 exceeds current real balance of 100"))
    assertEquals(reply(kit.call(WalletEntity.adjust)(Adjust("a2", "real", 5, " ", "c", "op", t0)).replyValue),
      ("REASON_REQUIRED", "A reason must be supplied for a manual balance adjustment"))
    assertEquals(reply(kit.call(WalletEntity.award)(Award("g", " ", 1, 1, 1, 0, None, Rules(), None, t0)).replyValue),
      ("MISSING_BONUS_REF", "bonus_ref is required"))
    assertEquals(reply(kit.call(WalletEntity.forfeit)(ForfeitAward("f", None, t0)).replyValue), ("NO_ACTIVE_BONUS", "No active bonus to forfeit"))
    assertEquals(reply(kit.call(WalletEntity.closePendingCap)(ClosePendingCap("cp", t0)).replyValue),
      ("NOT_PENDING_CAP", "No CONVERTED_PENDING_CAP bonus to close"))
    assertEquals(reply(kit.call(WalletEntity.switchPrimary)(SwitchPrimary("s", "zz", t0)).replyValue), ("GRANT_NOT_FOUND", "Grant zz not found"))
    assertEquals(reply(kit.call(WalletEntity.hold)(Hold("h", 5000, true, t0)).replyValue), ("INSUFFICIENT_FUNDS", "Insufficient real balance for withdrawal"))
    assertEquals(reply(kit.call(WalletEntity.confirm)(Confirm("cf", "nope", t0)).replyValue), ("REQUEST_NOT_PENDING", "Request nope is not the pending request"))
    assertEquals(reply(kit.call(WalletEntity.reverse)(Reverse("rv", "nope", t0)).replyValue),
      ("WITHDRAWAL_NOT_IN_COMPLETED_STATE", "Withdrawal nope is not completed"))
    assertEquals(reply(kit.call(WalletEntity.openMigrated)(OpenMigrated("m", 1, 0, Vector.empty, 0, 0, 0, t0)).replyValue),
      ("WALLET_ALREADY_OPEN", "Wallet already open"))
  }

  // ── spec 022 FR-008a: a split charged from a reservation ───────────────────

  test("a charge takes exactly the reservation, amount and unused together; one unit over is RESERVATION_SHORT") {
    def reserved(id: String) =
      val kit = EventSourcedTestKit.of(WalletEntity, id)
      kit.call(WalletEntity.deposit)(deposit("seed", 10000, t0))
      kit.call(WalletEntity.reserve)(Reserve("o1", 3000, t0))
      kit
    def charge(amount: Long, unused: Long, key: String = "t-1/ada") =
      ChargeReservationToAccount(key, "o1", "pool", "m1", Purpose.Split, key, amount, unused, t0)
    val over = reserved("charge-over/EUR")
    assertEquals(over.call(WalletEntity.chargeReservationToAccount)(charge(2900, 101)).replyValue.code, Some("RESERVATION_SHORT"))
    assertEquals((over.currentState.realBalance, over.currentState.reserved), (10000L, 3000L))
    // Exactly the reservation: the amount leaves the real balance, the unused part is released, the reservation is gone.
    val exact = reserved("charge-exact/EUR")
    assertEquals(exact.call(WalletEntity.chargeReservationToAccount)(charge(2900, 100)).replyValue.code, None)
    assertEquals((exact.currentState.realBalance, exact.currentState.reserved), (7100L, 0L))
    assert(!exact.currentState.reservations.contains("o1"))
  }
