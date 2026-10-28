package io.eitheror.casino.wallet.entity.fixtures

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.fixtures.FixtureRunner.*
import io.eitheror.casino.wallet.funding.{Record, Status}

/**
 * `award-bonus.test.tl` (FR-015). The original's route created the record before the guest ran
 * (record-first) and the guest only confirmed it; the rebuild's award creates the record itself.
 * So the fixture's pre-created record is not seeded: its fields become the award command's, and the
 * check is that the award produced that record.
 */
class AwardBonusFixturesSuite extends FixtureSuite("award-bonus"):

  private val recordFirst = "the record-first guard is dropped: an award creates its record (FR-015), so no award can name a missing or closed one"

  override protected def ignored: Map[String, String] = Map(
    "record_ref names a missing record — RESTRICTED_FUNDS_UNAVAILABLE, nothing written" -> recordFirst,
    "record_ref names a CLOSED record — RESTRICTED_FUNDS_UNAVAILABLE, nothing written"  -> recordFirst,
    "second grant — restricted_balance stays the primary's balance; the new grant is queued" -> Since002.TwoRecords
  )

  protected def check(c: Case): Unit =
    given Case = c
    val p        = c.params
    val ref      = text(p, "record_ref").get
    val awarded  = c.recordTwins.find(t => text(t, "id").contains(ref)).map(record).getOrElse(fail(s"no twin for $ref"))
    val existing = c.recordTwins.filterNot(t => text(t, "id").contains(ref)).map(record)
    val kit      = FixtureRunner.kit(c, opening(c, existing))
    val command = Award(
      key = text(p, "grant_ref").get, bonusRef = text(p, "bonus_ref").getOrElse(""), amount = long(p, "amount").get,
      wageringMultiplier = long(p, "wagering_multiplier").getOrElse(0L), maxConvertible = long(p, "max_win").getOrElse(0L),
      priority = awarded.record.priority, expiresAt = None, rules = Rules(), maxConcurrentAwards = None, at = c.at
    )
    val o = kit.call(WalletEntity.award)(command).replyValue
    accepted(o)
    val state = kit.currentState
    assertWalletAndRecords(state)
    assertResultBalances(o)
    val made = state.recordByGrant(command.key).getOrElse(fail("the award made no record")).record
    assertEquals((made.balance, made.status), (awarded.record.balance, Status.Active), s"${c.name}: the awarded record")
    c.result.foreach { r =>
      sameText("result bonus_status", text(r, "bonus_status"), made.status)
      same("result wagering_requirement", long(r, "wagering_requirement"), made.releaseRequirement)
      same("result wagered_amount", long(r, "wagered_amount"), made.releasedProgress)
      same("result max_win", long(r, "max_win"), made.maxConvertible)
    }
    assertEquals(made.releaseRequirement, awarded.record.releaseRequirement, s"${c.name}: wagering requirement")

/**
 * `cancel-bonus.test.tl` (FR-017). The original's route computed the clawback and passed it in
 * (`clawback_amount`); the rebuild computes it from the baseline recorded at award (FR-015a). Each
 * case seeds the records' baseline so the wallet's formula yields the fixture's clawback; with no
 * record at all, a closed earlier award carries it ("the award's baseline", restricted-funds.feature).
 */
class CancelBonusFixturesSuite extends FixtureSuite("cancel-bonus"):

  override protected def ignored: Map[String, String] = Map(
    "cancel bonus — clawback to zero closes a second, cap record" -> Since002.CapCloseAtZero
  )

  protected def check(c: Case): Unit =
    given Case = c
    val real     = long(c.wallet, "real_balance").getOrElse(0L)
    val clawback = long(c.params, "clawback_amount").getOrElse(0L)
    val baseline = Some(baselineFor(real, clawback))
    val twins    = c.recordTwins.map(record).map(_.copy(baseline = baseline))
    val records =
      if twins.nonEmpty || clawback == 0 then twins
      else Vector(AwardedRecord(Record("restricted-funds/earlier-award", grantRef = "earlier-award", status = Status.Forfeited),
        bonusRef = "", expiresAt = None, awardedAt = DefaultAt.minusSeconds(86400), baseline = baseline))
    val kit    = FixtureRunner.kit(c, opening(c, records))
    val before = kit.currentState
    val result = kit.call(WalletEntity.cancel)(CancelAward("fixture-cancel", text(c.params, "grant_ref"), c.at))
    val o      = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        assertWalletAndRecords(kit.currentState)
        assertResultBalances(o)
        c.result.foreach { r =>
          same("result clawback_applied", long(r, "clawback_applied"), o.clawbackApplied.get)
          sameSet("result closed_records", text(r, "closed_records"), o.forfeited)
        }
        c.transactions("bonus_cancel").foreach { t =>
          same("bonus_cancel balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("bonus_cancel real_balance_before", long(t, "real_balance_before"), before.realBalance)
          same("bonus_cancel real_balance_after", long(t, "real_balance_after"), o.realBalance)
        }

/** `forfeit-bonus.test.tl` (FR-016): the primary or the named active record, zeroed and forfeited. */
class ForfeitBonusFixturesSuite extends FixtureSuite("forfeit-bonus"):

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = FixtureRunner.kit(c, opening(c, c.recordTwins.map(record)))
    val before = kit.currentState
    val result = kit.call(WalletEntity.forfeit)(ForfeitAward("fixture-forfeit", text(c.params, "grant_ref"), c.at))
    val o      = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        assertWalletAndRecords(kit.currentState)
        assertResultBalances(o)
        c.result.foreach(r => sameSet("result closed_records", text(r, "closed_records"), o.forfeited))
        val forfeited = result.events.collectFirst { case e: WalletEvent.RestrictedFundsForfeited => e }.get
        c.transactions("bonus_credit").foreach { t =>
          same("forfeit transaction balance_before", long(t, "balance_before"), forfeited.forfeits.map(_.amount).sum)
        }

/** `close-pending-cap-bonus.test.tl` (FR-018): the first record awaiting its cap closes; nothing moves. */
class ClosePendingCapBonusFixturesSuite extends FixtureSuite("close-pending-cap-bonus"):

  override protected def ignored: Map[String, String] = Map(
    "grant_ref names the second of two caps — only that one closes"          -> Since002.TwoRecords,
    "grant_ref names no pending-cap record — NOT_PENDING_CAP, nothing closed" -> Since002.TwoRecords
  )

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = FixtureRunner.kit(c, opening(c, c.recordTwins.map(record)))
    val before = kit.currentState
    val result = kit.call(WalletEntity.closePendingCap)(ClosePendingCap("fixture-close", c.at))
    val o      = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        assertWalletAndRecords(kit.currentState)
        c.result.foreach(r => sameSet("result closed_records", text(r, "closed_records"), o.closedRecordId))
        assertEquals((kit.currentState.realBalance, kit.currentState.restrictedBalance), (before.realBalance, before.restrictedBalance),
          s"${c.name}: closing a pending cap moved money")
