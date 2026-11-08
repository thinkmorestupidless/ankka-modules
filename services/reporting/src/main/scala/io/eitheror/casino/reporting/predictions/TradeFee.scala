package io.eitheror.casino.reporting.predictions

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}
import io.eitheror.casino.reporting.intake.{BookEvent, FactId, Generation, Moved, WalletBook}

/** A trade's taker fee as `trade_made` states it: the market, the currency and the trade's day in the brand's zone. */
final case class Traded(market: String, currency: String, day: String, fee: Long)

/** A movement of the fee account under a trade (`reference` the trade id): + into the account, − out of it. */
final case class Credited(factId: String, currency: String, day: String, amount: Long)

/**
 * A trade's fee beside the fee account's credits under it (FR-031; data-model.md *The day*, `predictions`), key value,
 * `g<n>/<trade>`: `traded` once its `trade_made` is folded, `credited` once a fee movement names it, so either may come
 * first. The day is the trade's, else the first credit's; a trade whose fee and credits differ, or that one side
 * lacks, is listed at the close of that day.
 */
final case class TradeFeeState(
    generation: Int = 0,
    trade: String = "",
    market: String = "",
    currency: String = "",
    day: String = "",
    fee: Long = 0,
    traded: Boolean = false,
    credits: Long = 0,
    credited: Boolean = false,
    seen: Vector[String] = Vector.empty
):
  /** A trade with no fee and no movement agrees; a movement under no trade, or a fee unlike its credits, does not. */
  def differs: Boolean = fee != credits || (credited && !traded)

/** The generation and the trade are the view's, from the entity's id; the entity keeps neither. */
final class TradeFee(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[TradeFeeState]:
  def emptyState: TradeFeeState = TradeFeeState()

  def traded(t: Traded): Effect[TradeFeeState] =
    if currentState.traded then effects.reply(currentState)
    else effects.updateState(currentState.copy(market = t.market, currency = t.currency, day = t.day, fee = t.fee, traded = true))
      .thenReplyState

  def credited(c: Credited): Effect[TradeFeeState] =
    if currentState.seen.contains(c.factId) then effects.reply(currentState)
    else
      val s = currentState
      effects.updateState(s.copy(currency = if s.currency.nonEmpty then s.currency else c.currency,
        day = if s.day.nonEmpty then s.day else c.day, credits = s.credits + c.amount, credited = true,
        seen = FactId.remember(s.seen, c.factId))).thenReplyState

  def get: ReadOnlyEffect[TradeFeeState] = effects.reply(currentState)

object TradeFee extends KeyValueEntity.Companion[TradeFee, TradeFeeState](
      componentId = ComponentId("trade-fee"),
      stateSerializer = ReportingCodecs.serializer[TradeFeeState]("trade-fee")
    ):
  given Serializer[Traded]        = ReportingCodecs.serializer[Traded]("trade-fee-traded")
  given Serializer[Credited]      = ReportingCodecs.serializer[Credited]("trade-fee-credited")
  given Serializer[TradeFeeState] = ReportingCodecs.serializer[TradeFeeState]("trade-fee-reply")

  /** The fee account's kind (spec 022, amended by spec 023 FR-015). */
  val FeeAccount = "fee"

  def id(generation: Int, trade: String): String = Generation.id(generation, trade)

  def create(context: KeyValueEntityContext) = new TradeFee(context)

  val traded   = command("traded")(_.traded)
  val credited = command("credited")(_.credited)
  val get      = query("get")(_.get)

/**
 * The fee account's movements, from the wallets' `account_debit` (a taker charged into the account) and
 * `account_credit` (the account paying out) under the `fee` kind, into the trade they name. They move no casino figure.
 */
final class FeeCreditsConsumer(client: ComponentClient, settings: Settings) extends Consumer[BookEvent, Nothing]:
  def onMessage(e: BookEvent): Effect = e match
    case m: Moved if Generation.belongs(messageContext.subject, settings.generation) && m.accountKind.contains(TradeFee.FeeAccount) &&
        m.reference.nonEmpty =>
      val amount = if m.family == "account_credit" then -m.amount else m.amount
      client.forKeyValueEntity(EntityId(TradeFee.id(settings.generation, m.reference))).call(TradeFee.credited)
        .invoke(Credited(m.factId, m.currency, m.day, amount)): Unit
      effects.done()
    case _ => effects.ignore()

final class FeeCredits(settings: Settings)
    extends Consumer.Companion[FeeCreditsConsumer, BookEvent, Nothing](
      Generation.componentId("fee-credits", settings.generation), ChangeSource.eventsOf(WalletBook)):
  def create(ctx: ConsumerContext) = new FeeCreditsConsumer(ctx.componentClient, settings)

final class TradeFeeRows extends View[TradeFeeState, TradeFeeState]:
  def onChange(state: TradeFeeState): Effect =
    val subject = updateContext.subject
    Generation.of(subject).fold(effects.ignore())(g => effects.updateRow(state.copy(generation = g, trade = Generation.rest(subject))))

/** Every trade's fee and credits, by day (FR-031): what the day's close lists and sums per market. */
object TradeFees extends View.Companion[TradeFeeRows, TradeFeeState, TradeFeeState](
      componentId = ComponentId("trade-fees"),
      source = ChangeSource.stateOf(TradeFee),
      rowSerializer = ReportingCodecs.row[TradeFeeState]("trade-fee-row")
    ):
  val onDay = query("on-day")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'day' = :day ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new TradeFeeRows

  def ofDay(views: ViewClient, generation: Int, day: String): Vector[TradeFeeState] =
    views.forView(TradeFees).ask(onDay, 10_000_000, "generation" -> generation.toString, "day" -> day)
