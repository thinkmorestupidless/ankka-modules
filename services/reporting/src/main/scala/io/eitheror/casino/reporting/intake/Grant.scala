package io.eitheror.casino.reporting.intake

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}

import java.time.Instant

/**
 * One grant (data-model.md `Grant`, id `g<n>/<bonus id>`): the player, the offer it was granted under with its name
 * and type (spec 011 FR-027; no topic lists offers), its currency and amount, when, its status (`active`,
 * `converted`, `forfeited`, `cancelled`, `expired`) and what its free rounds won.
 */
final case class GrantState(
    bonusId: String = "",
    player: String = "",
    offerId: String = "",
    name: String = "",
    offerType: String = "",
    currency: Option[String] = None,
    amount: Long = 0,
    grantedAt: Option[Instant] = None,
    status: String = "active",
    endedAt: Option[Instant] = None,
    freeRoundsWin: Long = 0,
    seen: Vector[String] = Vector.empty
)

sealed trait GrantEvent:
  def factId: String
  def bonusId: String
  def player: String
  def at: Instant

object GrantEvent:
  /** A funds grant has its amount and currency; a free-rounds grant has neither, and its cost is what it wins. */
  final case class Granted(factId: String, bonusId: String, player: String, offerId: String, name: String,
      offerType: String, amount: Long, currency: Option[String], at: Instant, freeRounds: Int = 0) extends GrantEvent
  final case class Converted(factId: String, bonusId: String, player: String, at: Instant) extends GrantEvent
  final case class Forfeited(factId: String, bonusId: String, player: String, reason: String, at: Instant) extends GrantEvent
  final case class Cancelled(factId: String, bonusId: String, player: String, at: Instant) extends GrantEvent
  final case class Expired(factId: String, bonusId: String, player: String, at: Instant) extends GrantEvent
  /** What a free-rounds grant paid, at its finalisation and again for each late round (spec 011): bonus cost. */
  final case class FreeSpinsSettled(factId: String, bonusId: String, player: String, win: Long, currency: String,
      at: Instant) extends GrantEvent

object Grants:
  import GrantEvent.*

  /** The grant a fact is about; a code entry is about none. */
  def bonusId(fact: f.CasinoRewardsFact): Option[String] = fact match
    case x: f.BonusGranted     => Some(x.bonusId)
    case x: f.BonusCancelled   => Some(x.bonusId)
    case x: f.BonusForfeited   => Some(x.bonusId)
    case x: f.BonusCompleted   => Some(x.bonusId)
    case x: f.GrantExpired     => Some(x.bonusId)
    case x: f.FreeSpinsGranted => Some(x.bonusId)
    case x: f.FreeSpinsSettled => Some(x.bonusId)
    case _: f.BonusCodeEntered => None

  def event(s: GrantState, factId: String, fact: f.CasinoRewardsFact): Option[GrantEvent] =
    if s.seen.contains(factId) then None
    else fact match
      case x: f.BonusGranted =>
        Some(Granted(factId, x.bonusId, x.playerId, x.offerId, x.bonusName, x.offerType, x.amountCents.value, Some(x.currency.code), x.occurredAt))
      case x: f.FreeSpinsGranted =>
        Some(Granted(factId, x.bonusId, x.playerId, x.offerId, x.bonusName, x.offerType, 0L, None, x.occurredAt, x.rounds))
      case x: f.BonusCompleted   => Some(Converted(factId, x.bonusId, x.playerId, x.occurredAt))
      case x: f.BonusForfeited   => Some(Forfeited(factId, x.bonusId, x.playerId, x.reason, x.occurredAt))
      case x: f.BonusCancelled   => Some(Cancelled(factId, x.bonusId, x.playerId, x.occurredAt))
      case x: f.GrantExpired     => Some(Expired(factId, x.bonusId, x.playerId, x.occurredAt))
      case x: f.FreeSpinsSettled => Some(FreeSpinsSettled(factId, x.bonusId, x.playerId, x.winCents.value, x.currency.code, x.occurredAt))
      case _: f.BonusCodeEntered => None

  def apply(s0: GrantState, e: GrantEvent): GrantState =
    val s = s0.copy(bonusId = e.bonusId, player = e.player, seen = FactId.remember(s0.seen, e.factId))
    e match
      case g: Granted =>
        s.copy(offerId = g.offerId, name = g.name, offerType = g.offerType, amount = g.amount,
          currency = g.currency.orElse(s.currency), grantedAt = Some(g.at))
      case c: Converted        => s.copy(status = "converted", endedAt = Some(c.at))
      case x: Forfeited        => s.copy(status = "forfeited", endedAt = Some(x.at))
      case c: Cancelled        => s.copy(status = "cancelled", endedAt = Some(c.at))
      case x: Expired          => s.copy(status = "expired", endedAt = Some(x.at))
      case w: FreeSpinsSettled => s.copy(freeRoundsWin = s.freeRoundsWin + w.win, currency = s.currency.orElse(Some(w.currency)))

/** A rewards fact. The contract's codec; the entity stores only its events. */
final case class GrantFact(factId: String, fact: f.CasinoRewardsFact)

final class Grant(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[GrantState, GrantEvent]:
  def emptyState: GrantState = GrantState()

  def applyEvent(event: GrantEvent): GrantState = Grants.apply(currentState, event)

  /** Whether the fact was new. */
  def record(c: GrantFact): Effect[Boolean] =
    Grants.event(currentState, c.factId, c.fact).fold(effects.reply(false))(e => effects.persist(e).thenReply(_ => true))

  def get: ReadOnlyEffect[GrantState] = effects.reply(currentState)

object Grant extends EventSourcedEntity.Companion[Grant, GrantState, GrantEvent](
      componentId = ComponentId("grant"),
      stateSerializer = ReportingCodecs.serializer[GrantState]("grant"),
      eventSerializer = ReportingCodecs.serializer[GrantEvent]("grant-event")
    ):
  given Serializer[GrantFact] = Serializer.json[GrantFact]("reporting.grant-fact")(using JsonCodecMaker.make[GrantFact])
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, bonusId: String): String = Generation.id(generation, bonusId)

  def create(context: EventSourcedEntityContext) = new Grant(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

/** `casino.rewards` into each grant's `Grant`; registered only when the brand runs `casino` (research R12). */
final class RewardsFactsConsumer(client: ComponentClient, settings: Settings) extends Consumer[Option[f.CasinoRewardsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoRewardsFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.CasinoRewards.name, messageContext.subject)
      effects.ignore()
    case Some(r) =>
      Grants.bonusId(r).fold(effects.ignore()) { bonus =>
        client.forEventSourcedEntity(EntityId(Grant.id(settings.generation, bonus))).call(Grant.record)
          .invoke(GrantFact(FactId.of(f.CasinoRewards, r), r)): Unit
        Coverage.observed(client, settings, f.CasinoRewards.name, FactId.occurredAt(r))
        effects.done()
      }

final class RewardsFacts(settings: Settings) extends Consumer.Companion[RewardsFactsConsumer, Option[f.CasinoRewardsFact], Nothing](
      Generation.componentId("rewards-facts", settings.generation), Lenient.from(f.CasinoRewards)):
  def create(ctx: ConsumerContext) = new RewardsFactsConsumer(ctx.componentClient, settings)
