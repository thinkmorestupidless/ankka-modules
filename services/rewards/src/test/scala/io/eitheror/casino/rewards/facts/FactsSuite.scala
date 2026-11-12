package io.eitheror.casino.rewards.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*
import io.eitheror.casino.rewards.reward.RewardEvent.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** contracts/facts.md and research R11: each event to at most one family, the audit's operator actions, and no personal value. */
class FactsSuite extends munit.FunSuite:
  private val at     = Instant.parse("2026-11-01T12:00:00Z")
  private val expires = Instant.parse("2026-11-08T12:00:00Z")
  private val eur    = Currency.unsafe("EUR")
  private val secret = Personal("the player told me on the phone")

  private val funds = RewardState(opened = true, rewardId = "r-1", playerId = "p-1", offerId = "o-1", offerName = "Welcome",
    offerType = "DEPOSIT_MATCH", status = RewardStatus.Active, faceValue = 5000L, currency = "EUR", source = Source.AffiliateCode,
    code = Some("welcome"), tag = Some("aff-7"))
  private val rounds = funds.copy(offerType = "FREE_SPINS", payloadType = PayloadType.FreeSpins,
    freeRounds = Some(FreeRoundsState(FreeRoundsTerms("g-1", "st8", 10, 20L, Destination.RealBalance), Tally(10))))

  private val opening = Opening("p-1", "o-1", "Welcome", "DEPOSIT_MATCH", PayloadType.BonusFunds, Decision(5000L, 0L, 0L, 0L, eur),
    source = Source.AffiliateCode)

  /** Every event, each with the family it is, or none. */
  private val table: Vector[(RewardEvent, RewardState, Option[String])] = Vector(
    (Targeted("r-1", opening, at), funds, None),
    (Selected(at, at), funds, None),
    (Deselected(at), funds, None),
    (Activated(Some(WalletAward(None, 0L)), at, None, at), funds, Some("BonusGranted")),
    (Activated(None, at, None, at), rounds, Some("FreeSpinsGranted")),
    (AwardRefused("LIMIT", at), funds, None),
    (Converted(at, at), funds, Some("BonusCompleted")),
    (RewardEvent.Forfeited(ForfeitReason.BonusExhausted, Some(100L), at), funds, Some("BonusForfeited")),
    (RewardEvent.Closed(CloseReason.RealBalanceZero, afterConversion = false, at), funds, Some("BonusCompleted")),
    (RewardEvent.Closed(CloseReason.CapExpired, afterConversion = true, at), funds, None),
    (RewardEvent.Expired(ExpireReason.ClaimExpired, RewardStatus.Active, at), funds, Some("GrantExpired")),
    (RewardEvent.Expired(ExpireReason.PendingExpired, RewardStatus.Pending, at), funds, None),
    (RewardEvent.Expired(ExpireReason.PendingExpired, RewardStatus.Available, at), funds, None),
    (RewardEvent.Cancelled(CancelReason.PlayerCancel, 300L, at), funds, Some("BonusCancelled")),
    (RewardEvent.Revoked(RevokeMode.Reclaim, "op-1", Some(secret), 200L, at), funds, Some("BonusCancelled")),
    (Switched(1, Some(3L), at), funds, None),
    (ProviderStatusRecorded("active", at), rounds, None),
    (RoundCounted("rd-1", 40L, 9, at), rounds, None),
    (FreeRoundsFinalised(40L, at), rounds, Some("FreeSpinsSettled")),
    (LatePaid("rd-11", 25L, at), rounds, Some("FreeSpinsSettled")),
    (LatePaid("rd-12", 0L, at), rounds, None)
  )

  private def fact(e: RewardEvent, s: RewardState) = Facts.reward("r-1", 4L, e, s)

  test("each event is at most one family"):
    table.foreach((e, s, family) => assertEquals(fact(e, s).map(_.fact.getClass.getSimpleName), family, e.toString))

  test("the close after conversion publishes nothing; the close of a reward never converted is its completion"):
    assertEquals(fact(RewardEvent.Closed(CloseReason.CapExhausted, afterConversion = true, at), funds), None)
    assert(fact(RewardEvent.Closed(CloseReason.FreeSpinsFinalised, afterConversion = false, at), rounds).exists(_.fact.isInstanceOf[f.BonusCompleted]))

  test("bonus_granted carries the reward, the offer, the amount, the currency, the source, the code and the tag; keyed by the player"):
    val Some(Facts.Published(g: f.BonusGranted, id)) = fact(Activated(None, expires, None, at), funds): @unchecked
    assertEquals(g, f.BonusGranted("p-1", "r-1", "Welcome", "o-1", "DEPOSIT_MATCH", MinorUnits.unsafe(5000L), eur, Some("welcome"), at,
      Some("affiliate_code"), Some("welcome"), Some("aff-7"), expiresAt = Some(expires)))
    assertEquals((g.key, id), ("p-1", "r-1/4"))

  test("bonus_granted's expiresAt is the activation's deadline, not the state's"):
    val Some(Facts.Published(g: f.BonusGranted, _)) = fact(Activated(None, expires, None, at), funds.copy(expiresAt = Some(at))): @unchecked
    assertEquals(g.expiresAt, Some(expires))

  test("free_spins_granted carries the rounds, the game, the expiry, and spin value × rounds in the reward's currency (G5, G6)"):
    val Some(Facts.Published(g: f.FreeSpinsGranted, _)) = fact(Activated(None, expires, None, at), rounds): @unchecked
    assertEquals((g.rounds, g.gameIds), (10, Vector("g-1")))
    assertEquals((g.expiresAt, g.amountCents, g.currency), (Some(expires), Some(MinorUnits.unsafe(200L)), Some(eur)))

  test("free_spins_granted with no spin value is 0 in the reward's currency (research R16.5)"):
    val noValue = rounds.copy(currency = "BRL",
      freeRounds = Some(FreeRoundsState(FreeRoundsTerms("g-1", "st8", 25, 0L, Destination.RealBalance), Tally(25))))
    val Some(Facts.Published(g: f.FreeSpinsGranted, _)) = fact(Activated(None, expires, None, at), noValue): @unchecked
    assertEquals((g.amountCents, g.currency), (Some(MinorUnits.unsafe(0L)), Some(Currency.unsafe("BRL"))))

  test("the expiry and the value are on the wire"):
    val Some(Facts.Published(g: f.FreeSpinsGranted, _)) = fact(Activated(None, expires, None, at), rounds): @unchecked
    val wire = bytes(g)
    assert(wire.contains("\"expires_at\":\"2026-11-08T12:00:00Z\"") && wire.contains("\"amount_cents\":200") && wire.contains("\"currency\":\"EUR\""), wire)
    assertEquals(f.CasinoRewards.serializer.fromBytes(f.CasinoRewards.serializer.toBytes(g)), g)

  test("bonus_cancelled carries the clawback and the reason: the player's cancel, the provider's failure, the revoke's mode"):
    def cancelled(e: RewardEvent, s: RewardState) = fact(e, s).map(_.fact).collect { case c: f.BonusCancelled => (c.amountCents.value, c.reason) }
    assertEquals(cancelled(RewardEvent.Cancelled(CancelReason.PlayerCancel, 300L, at), funds), Some((300L, Some("player_cancel"))))
    assertEquals(cancelled(RewardEvent.Cancelled(CancelReason.ProviderFailed, 0L, at), rounds), Some((0L, Some("provider_failed"))))
    assertEquals(cancelled(RewardEvent.Revoked(RevokeMode.Forfeit, "op-1", None, 0L, at), funds), Some((0L, Some("forfeit"))))
    assertEquals(cancelled(RewardEvent.Revoked(RevokeMode.Reclaim, "op-1", None, 200L, at), funds), Some((200L, Some("reclaim"))))
    assertEquals(cancelled(RewardEvent.Revoked(RevokeMode.Forfeit, "op-1", None, 0L, at), rounds), Some((0L, Some("free_spins_revoked"))))

  test("bonus_forfeited carries the reason, the amount and the currency"):
    val Some(Facts.Published(b: f.BonusForfeited, _)) = fact(RewardEvent.Forfeited(ForfeitReason.AdminAdjustEmptied, Some(100L), at), funds): @unchecked
    assertEquals((b.reason, b.amountCents.map(_.value), b.currency), ("admin_adjust_emptied", Some(100L), Some(eur)))

  test("free_spins_settled carries the amount paid"):
    val Some(Facts.Published(s: f.FreeSpinsSettled, _)) = fact(FreeRoundsFinalised(40L, at), rounds): @unchecked
    assertEquals((s.winCents.value, s.currency), (40L, eur))

  private def bytes(x: f.CasinoRewardsFact) = String(f.CasinoRewards.serializer.toBytes(x), UTF_8)

  test("no fact carries a personal value: the revoke's reason is not on bonus_cancelled"):
    table.foreach((e, s, _) => fact(e, s).foreach(p => assert(!bytes(p.fact).contains("phone"), bytes(p.fact))))

  test("the audit: an operator's revoke with its mode, naming the reason without its value; an operator's award"):
    val Some(Facts.Published(r, id)) =
      Facts.audit("goldvibe", "r-1", 9L, RewardEvent.Revoked(RevokeMode.Reclaim, "op-1", Some(secret), 200L, at), funds): @unchecked
    assertEquals((r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.reasonCode, r.service, id),
      ("rewards.revoked", "operator", "op-1", "reward", "r-1", Some("reclaim"), "rewards", "audit/reward/r-1/9"))
    assert(r.changes.contains(f.AuditChange("reason", None, None)), r.changes.toString)
    assert(!r.toString.contains("phone"), r.toString)
    val Some(Facts.Published(a, _)) =
      Facts.audit("goldvibe", "r-1", 3L, Activated(None, at, None, at), funds.copy(source = Source.OperatorManual, openedBy = Some("op-2"))): @unchecked
    assertEquals((a.action, a.actorId), ("rewards.awarded", "op-2"))
    assert(a.changes.contains(f.AuditChange("amount", None, Some("5000"))), a.changes.toString)

  test("nothing else is audited: a player's activation, a cancel, a revoke with no reason names none"):
    assertEquals(Facts.audit("goldvibe", "r-1", 3L, Activated(None, at, None, at), funds), None)
    assertEquals(Facts.audit("goldvibe", "r-1", 3L, RewardEvent.Cancelled(CancelReason.PlayerCancel, 0L, at), funds), None)
    val Some(Facts.Published(r, _)) =
      Facts.audit("goldvibe", "r-1", 9L, RewardEvent.Revoked(RevokeMode.Forfeit, "op-1", None, 0L, at), funds): @unchecked
    assert(!r.changes.exists(_.field == "reason"), r.changes.toString)
