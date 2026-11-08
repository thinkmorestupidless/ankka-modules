package io.eitheror.casino.reporting.predictions

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, Lenient}

import java.time.Instant

/**
 * A market as `markets.events` says it is (data-model.md `Markets`, research R12): the question, category and
 * currency from its draft and changes, its status and the times it moved, its resolution, the outcome it was first
 * resolved to and the one a correction moved it to, and whether it was voided. `status` is `draft`, `approved`,
 * `open`, `suspended`, `closed`, `resolved`, `voided` or `final`.
 */
final case class MarketRow(
    marketId: String = "",
    question: String = "",
    category: String = "",
    currency: String = "",
    status: String = "draft",
    draftedAt: Option[Instant] = None,
    openedAt: Option[Instant] = None,
    closedAt: Option[Instant] = None,
    resolvedAt: Option[Instant] = None,
    resolution: Option[String] = None,
    firstResolution: Option[String] = None,
    corrected: Option[String] = None,
    correctedAt: Option[Instant] = None,
    voided: Boolean = false,
    voidedAt: Option[Instant] = None,
    finalAt: Option[Instant] = None
):
  /** When the market's life began and, once it is resolved, voided or final, when it ended, for a period's filter. */
  def began: Option[Instant] = openedAt.orElse(draftedAt)
  def ended: Option[Instant] = finalAt.orElse(voidedAt).orElse(correctedAt).orElse(resolvedAt)

  /** Whether it was alive at any time from `from` (inclusive) to `to` (exclusive). */
  def during(from: Instant, to: Instant): Boolean = began.exists(_.isBefore(to)) && ended.forall(!_.isBefore(from))

object MarketRow:
  given Serializer[MarketRow] = ReportingCodecs.row[MarketRow]("market-row")

  def next(r0: MarketRow, market: String, e: f.MarketsEventsFact): MarketRow =
    val r = r0.copy(marketId = market)
    e match
      case d: f.MarketDrafted  => r.copy(question = d.question, category = d.category, currency = d.currency.code,
          draftedAt = r.draftedAt.orElse(Some(d.occurredAt)))
      case c: f.MarketChanged  => r.copy(question = c.question, category = c.category, currency = c.currency.code)
      case _: f.MarketApproved => r.copy(status = "approved")
      case o: f.MarketOpened   => r.copy(status = "open", openedAt = r.openedAt.orElse(Some(o.occurredAt)))
      case _: f.MarketSuspended => r.copy(status = "suspended")
      case _: f.MarketResumed  => r.copy(status = "open")
      case c: f.MarketClosed   => r.copy(status = "closed", closedAt = Some(c.occurredAt))
      case _: f.MarketCloseMoved => r
      case x: f.MarketResolved =>
        r.copy(status = "resolved", resolvedAt = r.resolvedAt.orElse(Some(x.occurredAt)), resolution = Some(x.outcomeId),
          firstResolution = r.firstResolution.orElse(Some(x.outcomeId)))
      case x: f.MarketCorrected =>
        r.copy(resolution = Some(x.outcomeId), corrected = Some(x.outcomeId), correctedAt = Some(x.occurredAt))
      case v: f.MarketVoided   => r.copy(status = "voided", resolution = Some("void"), voided = true, voidedAt = Some(v.occurredAt))
      case x: f.MarketFinal    => r.copy(status = "final", finalAt = Some(x.occurredAt))

/**
 * A plain view over the `markets.events` topic, one row per market (its `ce-subject`), registered only when the brand
 * runs `predictions`. It reads the topic itself, not an entity of the generation, so a rebuild leaves it as it is.
 */
final class MarketRows extends View[Option[f.MarketsEventsFact], MarketRow]:
  def onChange(event: Option[f.MarketsEventsFact]): Effect = event match
    case None =>
      Lenient.unreadable(f.MarketsEvents.name, updateContext.subject)
      effects.ignore()
    case Some(e) => effects.updateRow(MarketRow.next(rowState.getOrElse(MarketRow()), e.marketId, e))

object Markets extends View.Companion[MarketRows, Option[f.MarketsEventsFact], MarketRow](
      componentId = ComponentId("markets"),
      source = Lenient.from(f.MarketsEvents),
      rowSerializer = MarketRow.given_Serializer_MarketRow
    ):
  val every = query("every")(s"SELECT payload FROM $table ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new MarketRows

  /** Every market: a brand's markets are few, so the report filters them here. */
  def all(views: ViewClient): Vector[MarketRow] = views.forView(Markets).ask(every, 1_000_000)

/**
 * A market's figures as its `MarketBook` holds them, for the market report (data-model.md `MarketFigures`): ankka 0.16
 * gives a view no state of an event sourced entity, so the row folds the book's events as the book does, keeping the
 * figures and the holdings (for open interest), not the ids the book dedups by. The view sees each event once.
 */
final case class MarketFiguresRow(generation: Int, market: String = "", book: MarketBookState = MarketBookState()):
  def currency: String    = book.currency
  def contracts: Long     = book.contracts
  def notional: Long      = book.notional
  def trades: Long        = book.trades
  def traders: Long       = book.traders.size.toLong
  def openInterest: Long  = book.openInterest
  def fees: Long          = book.fees
  def payments: Long      = book.payments
  def remainder: Long     = book.remainder

final class MarketFiguresRows extends View[MarketBookEvent, MarketFiguresRow]:
  def onChange(e: MarketBookEvent): Effect =
    val subject = updateContext.subject
    Generation.of(subject).fold(effects.ignore()) { g =>
      val before = rowState.getOrElse(MarketFiguresRow(g, Generation.rest(subject)))
      val book   = MarketBooks.apply(before.book, e).copy(tradeIds = Set.empty, positionIds = Set.empty, seen = Vector.empty)
      effects.updateRow(before.copy(book = book))
    }

object MarketFigures extends View.Companion[MarketFiguresRows, MarketBookEvent, MarketFiguresRow](
      componentId = ComponentId("market-figures"),
      source = ChangeSource.eventsOf(MarketBook),
      rowSerializer = ReportingCodecs.row[MarketFiguresRow]("market-figures-row")
    ):
  val byMarkets = query("by-markets")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'market' = ANY(string_to_array(:ids, ',')) ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new MarketFiguresRows

  def of(views: ViewClient, generation: Int, ids: Iterable[String]): Map[String, MarketFiguresRow] =
    if ids.isEmpty then Map.empty
    else views.forView(MarketFigures).ask(byMarkets, 1_000_000, "generation" -> generation.toString, "ids" -> ids.mkString(","))
      .map(r => r.market -> r).toMap
