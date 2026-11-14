package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}
import io.eitheror.casino.reporting.days.Days

import java.time.Instant

/** What the game provider said of a round (`round_settled`); the game's name and type are the catalogue's, when it has them. */
final case class ProviderFigures(provider: String, providerRoundId: String, game: String, gameName: Option[String] = None,
    gameType: Option[String] = None, session: String, stake: Long, win: Long, outcome: String, at: Instant)

/**
 * What the wallet moved for a round: its stakes, wins and rollbacks, from `WalletBook`'s legs, and its first leg's time,
 * which places a round the provider's record lacks in the dispute report (FR-017).
 */
final case class WalletFigures(stake: Long = 0, win: Long = 0, rollback: Long = 0, at: Option[Instant] = None)

/**
 * One round as the provider and the wallet each saw it (data-model.md `RoundCheck`, key value,
 * `g<n>/<player>/<currency>/<round id>`), where the day-end's differences come from (research R7). `day` is the
 * round's day: the wallet's round day once a leg is seen, else the provider's settlement day in the zone. `legs`
 * holds the wallet legs' fact ids, so a redelivered leg is not added twice.
 */
final case class RoundCheckState(
    player: String = "",
    currency: String = "",
    roundId: String = "",
    provider: Option[ProviderFigures] = None,
    wallet: Option[WalletFigures] = None,
    day: String = "",
    legs: Vector[String] = Vector.empty
):
  /** `matched`, `differs`, `provider_only` or `wallet_only`; empty before either side. */
  def status: String = (provider, wallet) match
    case (Some(p), Some(w)) => if RoundChecks.agree(p, w) then RoundChecks.Matched else RoundChecks.Differs
    case (Some(_), None)    => RoundChecks.ProviderOnly
    case (None, Some(_))    => RoundChecks.WalletOnly
    case (None, None)       => ""

/** A provider's settlement of a round, with its day in the zone. */
final case class ProviderSettled(player: String, currency: String, roundId: String, figures: ProviderFigures, day: String)

/** One wallet leg of a round: `bet_placed`, `win_credited`, `bet_rolled_back` or `round_settled_as_loss`. */
final case class WalletLeg(factId: String, player: String, currency: String, roundId: String, family: String, amount: Long, day: String,
    at: Option[Instant] = None)

object RoundChecks:
  val Matched      = "matched"
  val Differs      = "differs"
  val ProviderOnly = "provider_only"
  val WalletOnly   = "wallet_only"

  private val Legs = 100

  /**
   * The two sides agree when the provider's win is the wallet's and the provider's stake less win, nothing for a
   * cancelled round, is what the wallet kept: its stakes, less rollbacks, less wins.
   */
  def agree(p: ProviderFigures, w: WalletFigures): Boolean =
    val providerNet = if p.outcome == f.RoundSettled.Outcome.Cancelled then 0L else p.stake - p.win
    val providerWin = if p.outcome == f.RoundSettled.Outcome.Cancelled then 0L else p.win
    providerWin == w.win && providerNet == w.stake - w.rollback - w.win

  def provider(s: RoundCheckState, c: ProviderSettled): RoundCheckState =
    s.copy(player = c.player, currency = c.currency, roundId = c.roundId, provider = Some(c.figures),
      day = if s.wallet.isDefined && s.day.nonEmpty then s.day else c.day)

  def wallet(s: RoundCheckState, l: WalletLeg): RoundCheckState =
    if s.legs.contains(l.factId) then s
    else
      val w0 = s.wallet.getOrElse(WalletFigures())
      val w  = w0.copy(at = (w0.at ++ l.at).minOption)
      val next = l.family match
        case "bet_placed"      => w.copy(stake = w.stake + l.amount)
        case "win_credited"    => w.copy(win = w.win + l.amount)
        case "bet_rolled_back" => w.copy(rollback = w.rollback + l.amount)
        case _                 => w
      s.copy(player = l.player, currency = l.currency, roundId = l.roundId, wallet = Some(next),
        day = if s.wallet.isEmpty then l.day else s.day, legs = (s.legs :+ l.factId).takeRight(Legs))

  val families: Set[String] = Set("bet_placed", "win_credited", "bet_rolled_back", "round_settled_as_loss")

final class RoundCheck(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RoundCheckState]:
  def emptyState: RoundCheckState = RoundCheckState()

  private def to(next: RoundCheckState): Effect[Boolean] =
    if next == currentState then effects.reply(false) else effects.updateState(next).thenReply(_ => true)

  def provider(c: ProviderSettled): Effect[Boolean] = to(RoundChecks.provider(currentState, c))
  def wallet(l: WalletLeg): Effect[Boolean]         = to(RoundChecks.wallet(currentState, l))
  def get: ReadOnlyEffect[RoundCheckState]          = effects.reply(currentState)

object RoundCheck extends KeyValueEntity.Companion[RoundCheck, RoundCheckState](
      componentId = ComponentId("round-check"),
      stateSerializer = ReportingCodecs.serializer[RoundCheckState]("round-check")
    ):
  given Serializer[ProviderSettled] = ReportingCodecs.serializer[ProviderSettled]("round-check-provider")
  given Serializer[WalletLeg]       = ReportingCodecs.serializer[WalletLeg]("round-check-wallet")
  given Serializer[RoundCheckState] = ReportingCodecs.serializer[RoundCheckState]("round-check-reply")
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, player: String, currency: String, roundId: String): String =
    Generation.id(generation, player, currency, roundId)

  def create(context: KeyValueEntityContext) = new RoundCheck(context)

  val provider = command("provider")(_.provider)
  val wallet   = command("wallet")(_.wallet)
  val get      = query("get")(_.get)

/** `casino.rounds` into each round's `RoundCheck`; registered only when the brand runs `casino` (research R12). */
final class RoundsFactsConsumer(client: ComponentClient, settings: Settings) extends Consumer[Option[f.CasinoRoundsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoRoundsFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.CasinoRounds.name, messageContext.subject)
      effects.ignore()
    case Some(r: f.RoundSettled) =>
      val figures = ProviderFigures(r.provider, r.providerRoundId, r.gameId, r.gameName, r.gameType, r.sessionId,
        r.stakeCents.value, r.winCents.value, r.outcome, r.occurredAt)
      client.forKeyValueEntity(EntityId(RoundCheck.id(settings.generation, r.playerId, r.currency.code, r.roundId)))
        .call(RoundCheck.provider)
        .invoke(ProviderSettled(r.playerId, r.currency.code, r.roundId, figures, Days.dayOf(r.occurredAt, settings.reportingZone))): Unit
      Coverage.observed(client, settings, f.CasinoRounds.name, r.occurredAt)
      effects.done()
    case Some(_: f.RoundOpened) => effects.ignore()

final class RoundsFacts(settings: Settings) extends Consumer.Companion[RoundsFactsConsumer, Option[f.CasinoRoundsFact], Nothing](
      Generation.componentId("rounds-facts", settings.generation), Lenient.from(f.CasinoRounds)):
  def create(ctx: ConsumerContext) = new RoundsFactsConsumer(ctx.componentClient, settings)

/** `WalletBook`'s casino round legs into each round's `RoundCheck`, on the round's day; another generation's books are passed over. */
final class RoundLegsConsumer(client: ComponentClient, settings: Settings) extends Consumer[BookEvent, Nothing]:
  def onMessage(event: BookEvent): Effect = event match
    case m: Moved if m.casino && RoundChecks.families.contains(m.family) && Generation.belongs(messageContext.subject, settings.generation) =>
      m.roundId.fold(effects.ignore()) { round =>
        client.forKeyValueEntity(EntityId(RoundCheck.id(settings.generation, m.player, m.currency, round))).call(RoundCheck.wallet)
          .invoke(WalletLeg(m.factId, m.player, m.currency, round, m.family, m.amount, m.roundDay.getOrElse(m.day), Some(m.at))): Unit
        effects.done()
      }
    case _ => effects.ignore()

final class RoundLegs(settings: Settings) extends Consumer.Companion[RoundLegsConsumer, BookEvent, Nothing](
      Generation.componentId("round-legs", settings.generation), ChangeSource.eventsOf(WalletBook)):
  def create(ctx: ConsumerContext) = new RoundLegsConsumer(ctx.componentClient, settings)
