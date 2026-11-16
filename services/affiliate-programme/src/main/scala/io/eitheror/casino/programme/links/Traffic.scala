package io.eitheror.casino.programme.links

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.runtime.ViewClient
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.players.{ClickState, PlayerFact, PlayerFactRow}

import java.time.ZoneId

/**
 * A brand's traffic for a period (spec 030 A3): the clicks, registrations, first deposits and qualified
 * first deposits, for the brand, per affiliate and per link. Every count is of rows written once per fact
 * (the `Clicks` and `PlayerFacts` views), never a counter, so a redelivered fact counts nothing twice.
 *
 * As the original's revenue fields: `clickCount` the clicks the brand recorded on the programme's links
 * (`S/ClickTrackingRoutes.scala`), `registrationCount` the players bound, `ftdCount` the brand's first
 * deposits of bound players. `qftdCount`, which the original's pages read and nothing in it wrote, is the
 * deposits that set a player's first-deposit guard: a player's first deposit at or above the minimum
 * deposit of a plan that pays CPA, once for the player's life. A click falls in the period of its
 * `occurredAt` in the brand's reporting zone, as every fact does here (research R14).
 *
 * Impressions and unique clicks are not counted: no fact carries an impression, and a click fact carries
 * no visitor's digest (the brand keeps it, spec 012 FR-006).
 */
final case class LinkTraffic(btag: String, clickCount: Long, registrationCount: Long, ftdCount: Long, qftdCount: Long)

final case class AffiliateTraffic(affiliateId: String, clickCount: Long, registrationCount: Long, ftdCount: Long, qftdCount: Long,
    links: Vector[LinkTraffic])

final case class TrafficAnswer(brandId: String, period: String, clickCount: Long, registrationCount: Long, ftdCount: Long,
    qftdCount: Long, affiliates: Vector[AffiliateTraffic])

object Traffic:
  given JsonValueCodec[TrafficAnswer] = ProgrammeCodecs.wire[TrafficAnswer]

  /** The outcome a player's row records for the deposit that set the first-deposit guard (`Intake.describe`). */
  val Qualified = "first-deposit"

  private final case class Tally(clicks: Long = 0, registrations: Long = 0, ftds: Long = 0, qftds: Long = 0):
    def +(o: Tally) = Tally(clicks + o.clicks, registrations + o.registrations, ftds + o.ftds, qftds + o.qftds)

  private def tallyOf(p: PlayerFactRow): Tally =
    Tally(
      registrations = if p.kind == PlayerFact.Registration && p.outcome == "bound" then 1 else 0,
      ftds = if p.kind == PlayerFact.FirstDeposit then 1 else 0,
      qftds = if p.outcome == Qualified then 1 else 0
    )

  /** The counts of these clicks and player rows, already held to the period: the brand's, per affiliate, per link. */
  def answer(brand: String, period: String, clicks: Iterable[ClickState], players: Iterable[PlayerFactRow]): TrafficAnswer =
    val tallies: Vector[((String, String), Tally)] =
      clicks.toVector.map(c => (c.affiliate, c.link) -> Tally(clicks = 1)) ++
        players.toVector.map(p => (p.affiliate, p.link.getOrElse("")) -> tallyOf(p)).filter(_._2 != Tally())
    val perLink = tallies.groupMapReduce(_._1)(_._2)(_ + _)
    val affiliates = perLink.groupBy(_._1._1).toVector.sortBy(_._1).map { (affiliate, links) =>
      val rows = links.toVector.sortBy(_._1._2).map { case ((_, link), t) => LinkTraffic(link, t.clicks, t.registrations, t.ftds, t.qftds) }
      val t    = links.values.foldLeft(Tally())(_ + _)
      AffiliateTraffic(affiliate, t.clicks, t.registrations, t.ftds, t.qftds, rows)
    }
    val total = perLink.values.foldLeft(Tally())(_ + _)
    TrafficAnswer(brand, period, total.clicks, total.registrations, total.ftds, total.qftds, affiliates)

  /** Whether a click is in the range: its period and day are those of its `occurredAt` in the brand's zone. */
  def holds(range: Periods.Range, c: ClickState, zone: ZoneId): Boolean =
    c.occurredAt.exists(at => range.holds(Periods.periodOf(at, zone), at, zone))

  /**
   * The `yyyy-MM-dd` bounds the `Clicks` view is asked between, a day wider than the range on each side so
   * that no zone moves a click out of them; `holds` then keeps the range's own.
   */
  def days(range: Periods.Range, zone: ZoneId): (String, String) =
    range.bounds(zone).fold(("0000-00-00", "9999-99-99")) { (from, until) =>
      (from.minus(java.time.Duration.ofDays(1)).toString.take(10), until.plus(java.time.Duration.ofDays(2)).toString.take(10))
    }

  /** A brand's clicks in the range, from the `Clicks` view. */
  def clicks(views: ViewClient, brand: String, range: Periods.Range, zone: ZoneId): Vector[ClickState] =
    val (from, until) = days(range, zone)
    views.forView(Clicks).ask(Clicks.ofBrand, "brand" -> brand, "from" -> from, "until" -> until).filter(holds(range, _, zone))
