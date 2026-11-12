package io.eitheror.casino.rewards.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.MinorUnits
import io.eitheror.casino.rewards.offers.{Bodies, Offer, OfferEvent}
import io.eitheror.casino.rewards.reward.{FreeRoundsTerms, RewardEvent, RewardState, RewardStatus, Source}
import io.eitheror.casino.rewards.reward.RewardEvent.*

import java.time.Instant

/**
 * `Reward`'s events as facts and audit records (contracts/facts.md, research R11), pure. `state` is the reward as it
 * stands: the player, the offer, the face value, the currency, the source, code and tag are fixed when it opens. A
 * fact names no personal value (FR-033): an operator's revoke reason is named in the audit, never written. The ids
 * are the event's, so a redelivered event is the same message.
 */
object Facts:
  final case class Published[F](fact: F, id: String)

  // ── casino.rewards ──────────────────────────────────────────────────────

  /** `ce-id` `<reward>/<seq>` (contracts/facts.md). */
  def rewardId(rewardId: String, sequence: Long): String = s"$rewardId/$sequence"

  /** Whether an event may be a fact, so a publisher reads the reward only for those. */
  def publishes(event: RewardEvent): Boolean = event match
    case _: Activated | _: Converted | _: RewardEvent.Forfeited | _: RewardEvent.Cancelled | _: RewardEvent.Revoked |
        _: FreeRoundsFinalised => true
    case e: RewardEvent.Closed  => !e.afterConversion
    case e: RewardEvent.Expired => e.from == RewardStatus.Active
    case e: LatePaid            => e.win > 0
    case _                      => false

  /**
   * A free-spins grant's value (spec 016 G6, research R16.5): the spin value, resolved for the reward's currency, times
   * the rounds, as the original's back office values a free-spins claim (`BonusRoutes.freeSpinsSummaryJson`: its stored
   * `face_value` is `0`). No spin value is `0`.
   */
  def freeSpinsValue(terms: FreeRoundsTerms): Long = math.max(0L, terms.spinValue) * terms.spinCount.toLong

  /** The revoke's `reason` on `bonus_cancelled` (research R11): its mode, or `free_spins_revoked` for free rounds. */
  val FreeSpinsRevoked = "free_spins_revoked"

  /**
   * The family an event is, or none (research R11): nothing for an opening, a selection, a refusal, a switch, the
   * provider's word, a round counted, a pending selection's expiry, or the close after a conversion (one completion
   * per reward, `customerio_dispatch/handler.go:372-379`).
   */
  def reward(id: String, sequence: Long, event: RewardEvent, s: RewardState): Option[Published[f.CasinoRewardsFact]] =
    val source   = Some(s.source.wire)
    val currency = s.currencyOf
    def money(v: Long) = MinorUnits.unsafe(v)
    val fact: Option[f.CasinoRewardsFact] = Option.when(publishes(event))(event).collect {
      case e: Activated =>
        s.freeRounds match
          case Some(r) =>
            f.FreeSpinsGranted(s.playerId, id, s.offerName, s.offerId, s.offerType, r.terms.spinCount, Vector(r.terms.gameId), e.at,
              source, s.code, s.tag, expiresAt = Some(e.expiresAt), amountCents = Some(money(freeSpinsValue(r.terms))),
              currency = Some(currency))
          case None =>
            f.BonusGranted(s.playerId, id, s.offerName, s.offerId, s.offerType, money(s.faceValue), currency, s.code, e.at, source, s.code,
              s.tag, expiresAt = Some(e.expiresAt))
      case e: Converted => f.BonusCompleted(s.playerId, id, s.offerName, s.offerId, s.offerType, e.at, source, s.code, s.tag)
      case e: RewardEvent.Closed => f.BonusCompleted(s.playerId, id, s.offerName, s.offerId, s.offerType, e.at, source, s.code, s.tag)
      case e: RewardEvent.Forfeited =>
        f.BonusForfeited(s.playerId, id, s.offerName, s.offerId, s.offerType, e.reason.wire, e.at, e.amount.map(money), Some(currency),
          source, s.code, s.tag)
      case e: RewardEvent.Cancelled =>
        f.BonusCancelled(s.playerId, id, s.offerName, s.offerId, s.offerType, money(e.clawback), currency, e.at, source, s.code, s.tag,
          Some(e.reason.wire))
      case e: RewardEvent.Revoked =>
        f.BonusCancelled(s.playerId, id, s.offerName, s.offerId, s.offerType, money(e.clawback), currency, e.at, source, s.code, s.tag,
          Some(if s.isFreeRounds then FreeSpinsRevoked else e.mode.wire))
      case e: RewardEvent.Expired => f.GrantExpired(s.playerId, id, s.offerName, s.offerId, s.offerType, e.at, source, s.code, s.tag)
      case e: FreeRoundsFinalised =>
        f.FreeSpinsSettled(s.playerId, id, s.offerName, s.offerId, s.offerType, money(e.amount), currency, e.at, source, s.code, s.tag)
      case e: LatePaid =>
        f.FreeSpinsSettled(s.playerId, id, s.offerName, s.offerId, s.offerType, money(e.win), currency, e.at, source, s.code, s.tag)
    }
    fact.map(Published(_, rewardId(id, sequence)))

  // ── platform.audit ──────────────────────────────────────────────────────

  private def to(field: String, after: String) = f.AuditChange(field, None, Some(after))

  /** A field whose value is personal: named, with no value. */
  private def named(field: String) = f.AuditChange(field, None, None)

  private def record(brand: String, id: String, action: String, operator: String, rewardId: String, changes: Vector[f.AuditChange],
      reasonCode: Option[String], at: Instant): Published[f.AuditRecord] =
    Published(f.AuditRecord(recordId = id, brand = brand, service = "rewards", action = action, actorKind = "operator",
      actorId = operator, approverId = None, subjectKind = "reward", subjectId = rewardId, outcome = "accepted",
      reasonCode = reasonCode, changes = changes, correlationId = id, causationId = id, recordClass = "money", occurredAt = at), id)

  /**
   * The operator's actions on a reward (contracts/facts.md): an operator's award, once the wallet confirmed it, and a
   * revoke with its mode and the reason named as personal. The offer book's audit is `offerAudit`, below.
   */
  def audit(brand: String, rewardId: String, sequence: Long, event: RewardEvent, s: RewardState): Option[Published[f.AuditRecord]] =
    val id     = s"audit/reward/$rewardId/$sequence"
    val common = Vector(to("player", s.playerId), to("offer", s.offerId), to("currency", s.currency))
    event match
      case e: Activated if s.source == Source.OperatorManual =>
        s.openedBy.map { operator =>
          record(brand, id, "rewards.awarded", operator, rewardId,
            common ++ Vector(to("amount", s.faceValue.toString), to("status", RewardStatus.Active.wire)), None, e.at)
        }
      case e: RewardEvent.Revoked =>
        Some(record(brand, id, "rewards.revoked", e.by, rewardId,
          common ++ Vector(to("mode", e.mode.wire), to("clawback", e.clawback.toString), to("status", RewardStatus.Revoked.wire)) ++
            e.reason.map(_ => named("reason")),
          Some(e.mode.wire), e.at))
      case _ => None

  /**
   * The offer book's changes (FR-003; contracts/facts.md): created, edited (the fields the edit named, with their new
   * values: an offer holds no personal value), deactivated, and reordered, each with the operator. `slug` is the book's
   * id; the record id is `audit/offers/<slug>/<seq>`, so a redelivered event is the same record.
   */
  def offerAudit(brand: String, slug: String, sequence: Long, event: OfferEvent): Published[f.AuditRecord] =
    val id = s"audit/offers/$slug/$sequence"
    def offerRecord(action: String, operator: String, kind: String, subject: String, changes: Vector[f.AuditChange], at: Instant) =
      Published(f.AuditRecord(recordId = id, brand = brand, service = "rewards", action = action, actorKind = "operator",
        actorId = operator, approverId = None, subjectKind = kind, subjectId = subject, outcome = "accepted", reasonCode = None,
        changes = changes, correlationId = id, causationId = id, recordClass = "operator", occurredAt = at), id)
    def value(o: Offer, field: String): Option[String] =
      Option(Bodies.render(o).get(field)).filterNot(_.isNull).map(n => if n.isValueNode then n.asText else n.toString)
    event match
      case OfferEvent.OfferCreated(o, by) =>
        offerRecord("rewards.offer_created", by, "offer", o.id,
          Vector(to("name", o.name), to("type", o.offerType.wire), to("active", "true")), o.createdAt)
      case OfferEvent.OfferEdited(o, changed, by) =>
        offerRecord("rewards.offer_edited", by, "offer", o.id, changed.map(field => f.AuditChange(field, None, value(o, field))),
          o.updatedAt)
      case OfferEvent.OfferDeactivated(offerId, by, at) =>
        offerRecord("rewards.offer_deactivated", by, "offer", offerId, Vector(f.AuditChange("active", Some("true"), Some("false"))), at)
      case OfferEvent.OffersReordered(order, by, at) =>
        offerRecord("rewards.offers_reordered", by, "offer-book", slug, Vector(to("order", order.mkString(","))), at)
