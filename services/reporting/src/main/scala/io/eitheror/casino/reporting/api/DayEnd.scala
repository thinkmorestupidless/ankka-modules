package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.reporting.day.{BrandDay, CurrencyDay, DayState, Delta, Difference}
import io.eitheror.casino.reporting.documents.Document
import io.eitheror.casino.reporting.render.Money

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.collection.immutable.ListMap

import Wire.*

final case class DayEndDeposit(provider: String, count: Long, amount: String)
final case class DayEndWithdrawal(gateway: String, count: Long, amount: String)
final case class DayEndGame(provider: String, gameId: String, gameName: String, providerGgr: String, walletGgr: String)

/** One currency's day-end (FR-018): the providers, the games, the wallet's GGR and the deposit pool's, and the close's figures. */
final case class DayEndCurrency(deposits: Vector[DayEndDeposit], withdrawals: Vector[DayEndWithdrawal], games: Vector[DayEndGame],
    walletGgr: String, depositPoolGgr: String, bonusCost: String, chargebacks: String, liability: String, pendingHolds: String)

final case class DayEndDifference(roundId: String, provider: String, providerStake: String, providerWin: String, walletStake: String,
    walletWin: String, status: String)

/** What a correction moved, in its currency's places; a registration moves no currency's money. */
final case class CorrectionFigures(currency: String, bets: String, wins: String, ggr: String, depositPoolGgr: String, bonusCost: String,
    chargebacks: String, ngr: String, deposits: String, depositCount: Long, withdrawals: String, withdrawalCount: Long,
    registrations: Long)

final case class DayEndCorrection(arrivedAt: String, day: String, figures: CorrectionFigures)

/** `GET /day-end/{day}` (contracts/routes.md, *New routes*). */
final case class DayEndAnswer(day: String, zone: String, closedAt: String, reference: String, currencies: ListMap[String, DayEndCurrency],
    differences: Vector[DayEndDifference], corrections: Vector[DayEndCorrection], openingCorrections: Vector[DayEndCorrection])

object DayEndAnswer:
  given JsonValueCodec[DayEndAnswer] = JsonCodecMaker.make[DayEndAnswer](
    CodecMakerConfig.withTransientEmpty(false).withTransientNone(true).withTransientDefault(false).withMapMaxInsertNumber(Int.MaxValue))

/**
 * The day-end (US6; FR-018, FR-019; research R7): rendered from the day's `DayFixed` as the entity holds it, every list in
 * a fixed order, so a closed day reads byte for byte the same until a late fact arrives, which only adds a correction.
 * Every player is counted: the test-account setting is the dashboard's and the revenue report's (FR-024). Read before
 * its close, `409 day_open`.
 */
object DayEnd:
  val DayOpen = "day_open"

  /** The day-end's own reference: the same on every read of the day in a generation, so a read is repeatable (SC-008). */
  def reference(brand: String, generation: Int, day: String): String =
    UUID.nameUUIDFromBytes(s"$brand/day-end/g$generation/$day".getBytes(UTF_8)).toString

  def state(r: Reads, day: LocalDate): DayState =
    r.components.forEventSourcedEntity(EntityId(BrandDay.id(r.generation, day.toString))).call(BrandDay.read).invoke()

  private def iso(t: Instant) = t.toString

  private def figures(d: Delta): CorrectionFigures =
    def m(v: Long) = Money(v, d.currency)
    val x = d.money
    CorrectionFigures(d.currency, m(x.stakes - x.rollbacks), m(x.wins), m(x.ggr), m(x.poolGgr), m(x.bonusCost), m(x.chargebacks), m(x.ngr),
      m(x.deposits), x.depositCount, m(x.withdrawals), x.withdrawalCount, d.registrations)

  private def currency(c: String, d: CurrencyDay): DayEndCurrency =
    def m(v: Long) = Money(v, c)
    val s = d.total
    DayEndCurrency(
      s.payments.toVector.sortBy(_._1).map((p, f) => DayEndDeposit(p, f.completed, m(f.value))),
      s.gateways.toVector.sortBy(_._1).map((g, f) => DayEndWithdrawal(g, f.completed, m(f.amount))),
      d.games.values.toVector.sortBy(g => (g.provider, g.game)).map(g =>
        DayEndGame(g.provider, g.game, g.gameName.getOrElse(g.game), m(g.providerStake - g.providerWin), m(g.walletStake - g.walletWin))),
      m(s.money.ggr), m(s.money.poolGgr), m(s.money.bonusCost), m(s.money.chargebacks), m(d.liabilityAtClose), m(d.pendingHoldsAtClose))

  private def difference(d: Difference): DayEndDifference =
    def m(v: Long) = Money(v, d.currency)
    DayEndDifference(d.roundId, d.provider, m(d.providerStake), m(d.providerWin), m(d.walletStake - d.walletRollback), m(d.walletWin),
      d.status)

  /** A closed day's day-end; `None` for a day not closed. */
  def of(r: Reads, day: LocalDate, s: DayState): Option[DayEndAnswer] =
    Option.when(s.closed && s.closedAt.isDefined) {
      DayEndAnswer(day.toString, s.zone, iso(s.closedAt.get), reference(r.settings.brand, r.generation, day.toString),
        ListMap.from(s.currencies.toVector.sortBy(_._1).map((c, d) => c -> currency(c, d))),
        s.differences.map(difference),
        s.corrections.map(c => DayEndCorrection(iso(c.arrivedAt), day.toString, figures(c.delta))),
        s.openingCorrections.map(o => DayEndCorrection(iso(o.arrivedAt), o.day, figures(o.delta))))
    }

  private def asked(ctx: Ctx, day: String): Either[Answer, (LocalDate, DayEndAnswer)] =
    for
      d <- io.eitheror.casino.reporting.ranges.Ranges.parse(day.trim).left.map(_ =>
        refuse(400, Refusal(Codes.InvalidDate, Some("day"), Some(s"day is not a date as YYYY-MM-DD, not $day"))))
      a <- of(ctx.reads, d, state(ctx.reads, d)).toRight(refuse(409, Refusal(DayOpen, message = Some(s"$d has not closed"))))
    yield (d, a)

  /** `GET /day-end/{day}`. */
  def read(ctx: Ctx, day: String): Answer = answered(asked(ctx, day).map((_, a) => ok(a)))

  /** `GET /day-end/{day}/export`: the day-end as CSV, a `day-end-export` document (FR-021). */
  def exported(ctx: Ctx, day: String, operator: String): Answer =
    answered(asked(ctx, day).map { (d, _) =>
      Exports.exported(ctx, Reports.Asked(None, None, None, Some("csv"), operator),
        Exports.Ask(Exports.DayEndReport, d, d, Map("day" -> d.toString), Document.DayEndExport))
    })

  /**
   * The day-end as rows of `section, currency, item, field, value` (every section in one file, a column layout no
   * section has to share): the document's reference, the day, the zone and the close time first, then each currency's
   * deposits, withdrawals, games and totals, the differences, the corrections and the opening corrections.
   */
  def rows(a: DayEndAnswer, documentReference: String): Vector[Vector[String]] =
    def row(section: String, currency: String, item: String, field: String, value: String) = Vector(section, currency, item, field, value)
    def corrections(section: String, cs: Vector[DayEndCorrection]) = cs.zipWithIndex.flatMap { (c, i) =>
      val f = c.figures
      val item = s"${i + 1}"
      Vector("arrivedAt" -> c.arrivedAt, "day" -> c.day, "bets" -> f.bets, "wins" -> f.wins, "ggr" -> f.ggr,
        "depositPoolGgr" -> f.depositPoolGgr, "bonusCost" -> f.bonusCost, "chargebacks" -> f.chargebacks, "ngr" -> f.ngr,
        "deposits" -> f.deposits, "depositCount" -> f.depositCount.toString, "withdrawals" -> f.withdrawals,
        "withdrawalCount" -> f.withdrawalCount.toString, "registrations" -> f.registrations.toString)
        .map((k, v) => row(section, f.currency, item, k, v))
    }
    Vector(row("day-end", "", "", "reference", documentReference), row("day-end", "", "", "dayEnd", a.reference),
      row("day-end", "", "", "day", a.day), row("day-end", "", "", "zone", a.zone), row("day-end", "", "", "closedAt", a.closedAt)) ++
      a.currencies.toVector.flatMap { (c, x) =>
        x.deposits.flatMap(p => Vector(row("deposits", c, p.provider, "count", p.count.toString), row("deposits", c, p.provider, "amount", p.amount))) ++
          x.withdrawals.flatMap(w => Vector(row("withdrawals", c, w.gateway, "count", w.count.toString),
            row("withdrawals", c, w.gateway, "amount", w.amount))) ++
          x.games.flatMap { g =>
            val item = s"${g.provider}/${g.gameId}"
            Vector(row("games", c, item, "gameName", g.gameName), row("games", c, item, "providerGgr", g.providerGgr),
              row("games", c, item, "walletGgr", g.walletGgr))
          } ++
          Vector("walletGgr" -> x.walletGgr, "depositPoolGgr" -> x.depositPoolGgr, "bonusCost" -> x.bonusCost, "chargebacks" -> x.chargebacks,
            "liability" -> x.liability, "pendingHolds" -> x.pendingHolds).map((k, v) => row("totals", c, "", k, v))
      } ++
      a.differences.flatMap(d => Vector("provider" -> d.provider, "providerStake" -> d.providerStake, "providerWin" -> d.providerWin,
        "walletStake" -> d.walletStake, "walletWin" -> d.walletWin, "status" -> d.status).map((k, v) => row("differences", "", d.roundId, k, v))) ++
      corrections("corrections", a.corrections) ++ corrections("opening-corrections", a.openingCorrections)

  val Header: Vector[String] = Vector("section", "currency", "item", "field", "value")
