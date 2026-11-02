package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.ledger.{LedgerRow, Revenue, RevenueAnswer}
import io.eitheror.casino.programme.ledger.Revenue.given
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.players.PlayerFactRow

/** The reads of figures both sides serve (M1 to M4, O22, O37 to O39), over one range of `period`. */
final class Reading(api: Api):
  /** The range a `period` token names (`yyyy-MM`, a month range, a day range, `all`), or the original's refusal. */
  def range(token: Option[String])(f: (String, Periods.Range) => Respond[Bytes]): Respond[Bytes] =
    token.flatMap(t => Periods.Range.parse(t).map(t -> _)) match
      case Some((t, r)) => f(t, r)
      case None         => refuse(400, Codes.InvalidPeriod, "period is yyyy-MM, yyyy-MM..yyyy-MM, yyyy-MM-dd..yyyy-MM-dd or all")

  private def zones(brands: Vector[String]) = brands.map(b => b -> api.brand(b).zone)

  def revenue(affiliate: Option[String], brands: Vector[String], token: Option[String]): Respond[Bytes] =
    range(token) { (t, r) => ok(RevenueAnswer(affiliate, t, Revenue.read(api.reads, affiliate, zones(brands), r))) }

  /** The rows of both views for the range, held to it in each brand's zone. */
  def rows(affiliate: Option[String], brands: Vector[String], r: Periods.Range): (Vector[LedgerRow], Vector[PlayerFactRow]) =
    val (from, to) = Revenue.bounds(r)
    val z          = zones(brands)
    (z.flatMap((b, zone) => api.reads.ledger(affiliate, b, from, to).filter(x => r.holds(x.period, x.occurredAt, zone))),
      z.flatMap((b, zone) => api.reads.players(affiliate, b, from, to).filter(x => r.holds(x.period, x.occurredAt, zone))))
