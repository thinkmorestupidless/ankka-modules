package io.eitheror.casino.reporting.api

import io.eitheror.casino.reporting.day.DaysView
import io.eitheror.casino.reporting.reads.Wallets
import io.eitheror.casino.reporting.settings.ReportingSettingsState

import Wire.*

/**
 * The currency a report or the dashboard answers in (FR-003; research R6): the one asked for among those the brand
 * holds figures in, else the brand's set default, else the currency most of the brand's wallets are in (ties by name),
 * else the original's `EUR`. Nothing is converted.
 */
object Currencies:
  /** The currency answered in and every currency the brand holds figures in, the configured default among them. */
  final case class Chosen(currency: String, currencies: Vector[String])

  def default(ctx: Ctx, settings: ReportingSettingsState, held: Vector[String]): String =
    settings.defaultCurrency.getOrElse {
      held.map(c => c -> Wallets.count(ctx.views, ctx.settings.generation, c)).sortBy((c, n) => (-n, c)).headOption.map(_._1)
        .getOrElse("EUR")
    }

  def choose(ctx: Ctx, asked: Option[String], settings: ReportingSettingsState): Either[Answer, Chosen] =
    val held = DaysView.currencies(ctx.views, ctx.settings.generation)
    Wire.currency(asked, held, settings.defaultCurrency, default(ctx, settings, held))
      .map(c => Chosen(c, (held ++ settings.defaultCurrency).distinct.sorted))
