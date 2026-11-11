package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.documents.Document
import io.eitheror.casino.reporting.ranges.Ranges.Period
import io.eitheror.casino.reporting.reads.{ComplianceRow, ComplianceRows, Compliance as ComplianceView}

import Wire.*

/** `GET /compliance/rg-cases`'s row (contracts/routes.md): the player by id, the case's type, times, outcome and reason. */
final case class RgCaseLine(playerId: String, `type`: String, openedAt: Option[String], closedAt: Option[String], outcome: String,
    reason: String)
final case class RgCases(rows: Vector[RgCaseLine])

/** `GET /compliance/kyc-decisions`'s row: the document's category (`status` for a status decision), its times, outcome and reason. */
final case class KycLine(playerId: String, category: String, submittedAt: Option[String], decidedAt: Option[String], outcome: String,
    reason: String)
final case class KycDecisions(rows: Vector[KycLine])

object ComplianceCodecs:
  given JsonValueCodec[RgCases]      = Wire.answer[RgCases]
  given JsonValueCodec[KycDecisions] = Wire.answer[KycDecisions]

/**
 * The compliance exports (US7; FR-020, FR-021): the period's RG cases, limits applied and self-exclusions, and its KYC
 * decisions, from `casino.players` through `Compliance`, in the brand's zone. As CSV each is a `compliance-export`
 * document, named `rg-cases-report-<reference>.csv` or `kyc-decisions-report-<reference>.csv`, the player ids it names
 * beside it, so an erasure ends it (FR-021).
 */
object Compliance:
  import ComplianceCodecs.given

  private def iso(t: Option[java.time.Instant]) = t.map(_.toString)

  private def rows(r: Reads, kind: String, period: Period): Vector[ComplianceRow] =
    ComplianceView.between(r.views, r.generation, kind, r.startOf(period.from), r.startOf(period.to.plusDays(1)))

  def rgLines(r: Reads, period: Period): Vector[RgCaseLine] =
    rows(r, ComplianceRows.Rg, period).map(c => RgCaseLine(c.player, c.tpe, iso(c.opened), iso(c.closed), c.outcome, c.reason))

  def kycLines(r: Reads, period: Period): Vector[KycLine] =
    rows(r, ComplianceRows.Kyc, period).map(c => KycLine(c.player, c.tpe, iso(c.opened), iso(c.closed), c.outcome, c.reason))

  val RgHeader: Vector[String]  = Vector("playerId", "type", "openedAt", "closedAt", "outcome", "reason")
  val KycHeader: Vector[String] = Vector("playerId", "category", "submittedAt", "decidedAt", "outcome", "reason")

  def rgRows(r: Reads, period: Period): Vector[Vector[String]] =
    rgLines(r, period).map(l => Vector(l.playerId, l.`type`, l.openedAt.getOrElse(""), l.closedAt.getOrElse(""), l.outcome, l.reason))

  def kycRows(r: Reads, period: Period): Vector[Vector[String]] =
    kycLines(r, period).map(l => Vector(l.playerId, l.category, l.submittedAt.getOrElse(""), l.decidedAt.getOrElse(""), l.outcome, l.reason))

  private def periodOf(ctx: Ctx, asked: Reports.Asked): Either[Answer, Period] =
    for
      period <- Wire.period(asked.from, asked.to, ctx.today)
      _      <- Wire.complete(period, Completeness.from(ctx.components, ctx.settings, Vector(f.CasinoPlayers.name)))
    yield period

  private def exportOf(ctx: Ctx, asked: Reports.Asked, report: String, period: Period): Answer =
    Exports.exported(ctx, asked, Exports.Ask(report, period.from, period.to, Map.empty, Document.ComplianceExport))

  /** `GET /compliance/rg-cases`. */
  def rgCases(ctx: Ctx, asked: Reports.Asked): Answer =
    answered(periodOf(ctx, asked).map(p =>
      if asked.csv then exportOf(ctx, asked, Exports.RgCasesReport, p) else ok(RgCases(rgLines(ctx.reads, p)))))

  /** `GET /compliance/kyc-decisions`. */
  def kycDecisions(ctx: Ctx, asked: Reports.Asked): Answer =
    answered(periodOf(ctx, asked).map(p =>
      if asked.csv then exportOf(ctx, asked, Exports.KycDecisionsReport, p) else ok(KycDecisions(kycLines(ctx.reads, p)))))
