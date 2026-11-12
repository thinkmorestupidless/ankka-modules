package io.eitheror.casino.publicapi.records

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean

import java.time.Instant

/**
 * A bonus award (data-model.md `BonusAwardRecord`, by `bonusId`, the wallet's `awardId`): the grant and its end from
 * `casino.rewards`, the requirement and progress from `money.transactions`. The two topics are not ordered against each
 * other, so each status is kept with its time and the later one is served (contracts/fields.md §2 BonusStatus).
 */
final case class BonusAwardState(
    id: String = "",
    playerId: String = "",
    offerId: Option[String] = None,
    name: Option[String] = None,
    offerType: Option[String] = None,
    valueCents: Option[Long] = None,
    currency: Option[String] = None,
    wageringRequirementCents: Option[Long] = None,
    wageredCents: Option[Long] = None,
    rewardStatus: Option[String] = None,
    rewardStatusAt: Option[Instant] = None,
    walletStatus: Option[String] = None,
    walletStatusAt: Option[Instant] = None,
    grantedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    applied: Vector[String] = Vector.empty
):
  /** The contract's status: the later of the reward's and the wallet's. */
  def status: Option[String] = (rewardStatus, walletStatus) match
    case (Some(r), Some(w)) => if walletStatusAt.exists(w0 => rewardStatusAt.forall(_.isBefore(w0))) then Some(w) else Some(r)
    case (r, w)             => r.orElse(w)

object BonusFold:
  def apply(s0: BonusAwardState, fact: f.CasinoRewardsFact): BonusAwardState =
    def named(s: BonusAwardState, id: String, name: String, offer: String, kind: String) =
      s.copy(id = id, playerId = fact.playerId, offerId = Some(offer), name = Option(name).filter(_.nonEmpty).orElse(s.name),
        offerType = Option(kind).filter(_.nonEmpty).orElse(s.offerType))
    def ended(s: BonusAwardState, status: String, at: Instant) = s.copy(rewardStatus = Some(status), rewardStatusAt = Some(at))
    fact match
      case x: f.BonusGranted =>
        ended(named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType), "ACTIVE", x.occurredAt)
          .copy(valueCents = Some(x.amountCents.value), currency = Some(x.currency.code), grantedAt = Some(x.occurredAt), expiresAt = x.expiresAt)
      case x: f.FreeSpinsGranted =>
        ended(named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType), "ACTIVE", x.occurredAt)
          .copy(valueCents = Some(x.amountCents.fold(0L)(_.value)), currency = x.currency.map(_.code).orElse(s0.currency),
            grantedAt = Some(x.occurredAt), expiresAt = x.expiresAt)
      case x: f.BonusCancelled  => ended(named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType), "CANCELLED", x.occurredAt)
      case x: f.BonusForfeited  => ended(named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType), "FORFEITED", x.occurredAt)
      case x: f.BonusCompleted  => ended(named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType), "COMPLETED", x.occurredAt)
      case x: f.GrantExpired    => ended(named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType), "EXPIRED", x.occurredAt)
      case x: f.FreeSpinsSettled => named(s0, x.bonusId, x.bonusName, x.offerId, x.offerType)
      case _                    => s0

  def bonusId(fact: f.CasinoRewardsFact): Option[String] = fact match
    case x: f.BonusGranted     => Some(x.bonusId)
    case x: f.FreeSpinsGranted => Some(x.bonusId)
    case x: f.BonusCancelled   => Some(x.bonusId)
    case x: f.BonusForfeited   => Some(x.bonusId)
    case x: f.BonusCompleted   => Some(x.bonusId)
    case x: f.GrantExpired     => Some(x.bonusId)
    case x: f.FreeSpinsSettled => Some(x.bonusId)
    case _                     => None

  /** The wallet's record status as the contract's (contracts/fields.md §2); `CLOSED` is CLOSED, not COMPLETED. */
  def walletWord(status: String): Option[String] = status match
    case "ACTIVE" | "CONVERTED_PENDING_CAP" | "CLOSED" | "FORFEITED" => Some(status)
    case _                                                          => None

final case class BonusFact(factId: String, fact: f.CasinoRewardsFact)
final case class BonusMoney(factId: String, playerId: String, requirementCents: Option[Long], progressCents: Option[Long],
    status: Option[String], at: Instant)

final class BonusAwardRecord(@scala.annotation.unused context: KeyValueEntityContext) extends RecordEntity[BonusAwardState]:
  def emptyState: BonusAwardState                                   = BonusAwardState()
  protected def applied(s: BonusAwardState): Vector[String]        = s.applied
  protected def remembered(s: BonusAwardState, ids: Vector[String]) = s.copy(applied = ids)

  def reward(c: BonusFact): Effect[Boolean] = once(c.factId)(BonusFold(_, c.fact))
  def money(c: BonusMoney): Effect[Boolean] = once(c.factId) { s =>
    val withStatus = c.status.flatMap(BonusFold.walletWord) match
      case Some(w) if s.walletStatusAt.forall(!_.isAfter(c.at)) => s.copy(walletStatus = Some(w), walletStatusAt = Some(c.at))
      case _                                                   => s
    withStatus.copy(playerId = if s.playerId.isEmpty then c.playerId else s.playerId,
      wageringRequirementCents = c.requirementCents.orElse(s.wageringRequirementCents), wageredCents = c.progressCents.orElse(s.wageredCents))
  }
  def get: ReadOnlyEffect[BonusAwardState] = effects.reply(currentState)

object BonusAwardRecord extends KeyValueEntity.Companion[BonusAwardRecord, BonusAwardState](
      componentId = ComponentId("bonus-award-record"),
      stateSerializer = PublicApiCodecs.serializer[BonusAwardState]("bonus-award-record")
    ):
  given Serializer[BonusFact]       = Serializer.json[BonusFact]("public-api.bonus-fact")(using JsonCodecMaker.make[BonusFact])
  given Serializer[BonusMoney]      = PublicApiCodecs.serializer[BonusMoney]("bonus-money")
  given Serializer[BonusAwardState] = PublicApiCodecs.serializer[BonusAwardState]("bonus-award-reply")

  def create(context: KeyValueEntityContext) = new BonusAwardRecord(context)

  val reward = command("reward")(_.reward)
  val money  = command("money")(_.money)
  val get    = query("get")(_.get)
