package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, PlayerLog, PlayerLogEvent}

import java.time.Instant

/**
 * One row of a compliance export (data-model.md `Compliance`; FR-020): an RG case, a limit applied or a self-exclusion
 * (`kind` `rg`), or a KYC document's decision or a KYC status decision (`kind` `kyc`). `tpe` is the case's type, the
 * limit's or `self_exclusion` for RG and the document's category (`status` for a status decision) for KYC; `opened` is
 * when a case opened or a document was submitted, `closed` when it closed or was decided. The player by id only.
 */
final case class ComplianceRow(generation: Int, kind: String = "", player: String = "", tpe: String = "", opened: Option[Instant] = None,
    closed: Option[Instant] = None, outcome: String = "", reason: String = "")

object ComplianceRow:
  given Serializer[ComplianceRow] = ReportingCodecs.row[ComplianceRow]("compliance-row")

object ComplianceRows:
  val Rg  = "rg"
  val Kyc = "kyc"

  /** A self-exclusion's row type, and a KYC status decision's category. */
  val SelfExclusion = "self_exclusion"
  val Status        = "status"
  val Applied       = "applied"

  /**
   * What a `PlayerLog` event writes, as `(key, row from the row before)`: a case's opening and closing meet in one row
   * under `rg/<case>`; a limit applied and a self-exclusion are rows of their own under their fact id; a document's
   * decision is `kyc/<document>`, a status decision `kyc/<player>/<at>`. A pending KYC status is not a decision.
   */
  def of(g: Int, e: PlayerLogEvent): Option[(String, Option[ComplianceRow] => ComplianceRow)] =
    def row(before: Option[ComplianceRow], kind: String) = before.getOrElse(ComplianceRow(g, kind, e.player))
    e match
      case o: PlayerLogEvent.RgCaseOpened =>
        Some(s"rg/${o.rgCase}" -> (b => row(b, Rg).copy(tpe = o.caseType, opened = Some(o.at), reason = o.trigger)))
      case c: PlayerLogEvent.RgCaseClosed =>
        Some(s"rg/${c.rgCase}" -> (b => row(b, Rg).copy(tpe = c.caseType, closed = Some(c.at), outcome = c.closure)))
      case l: PlayerLogEvent.RgLimitApplied =>
        Some(s"rg/limit/${l.factId}" -> (b => row(b, Rg).copy(tpe = l.limitType, opened = Some(l.at), outcome = Applied, reason = l.causedBy)))
      case s: PlayerLogEvent.StatusChanged if s.family == "player_self_excluded" =>
        Some(s"rg/self-exclusion/${s.factId}" -> (b => row(b, Rg).copy(tpe = SelfExclusion, opened = Some(s.at), closed = s.until,
          outcome = s.to, reason = s.reason.getOrElse(""))))
      case d: PlayerLogEvent.KycDocumentDecided =>
        Some(s"kyc/${d.document}" -> (b => row(b, Kyc).copy(tpe = d.category, opened = d.submitted, closed = Some(d.decided),
          outcome = d.decision, reason = d.reason.getOrElse(""))))
      case k: PlayerLogEvent.KycChanged if k.family == "player_kyc_verified" || k.family == "player_kyc_rejected" =>
        Some(s"kyc/${k.player}/${k.at.toEpochMilli}" -> (b => row(b, Kyc).copy(tpe = Status, opened = k.submitted, closed = Some(k.at), outcome = k.to,
          reason = k.reason.getOrElse(""))))
      case _ => None

final class ComplianceView extends KeyedView[ComplianceRow]:
  def onPlayer(event: PlayerLogEvent, change: Change): Effect =
    Generation.of(change.subject).fold(effects.ignore()) { g =>
      ComplianceRows.of(g, event).fold(effects.ignore()) { (key, next) =>
        val k = Generation.id(g, key)
        effects.updateRow(k, next(change.rows.get(k)))
      }
    }

object Compliance extends KeyedView.Companion[ComplianceView, ComplianceRow](
      ComponentId("compliance"),
      ComplianceRow.given_Serializer_ComplianceRow
    ):
  val players = source(ChangeSource.eventsOf(PlayerLog))(_.onPlayer)

  /** The RG rows opened or closed from `from` (inclusive) to `to` (exclusive), by when they opened. */
  val rgBetween = query("rg-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'kind' = '${ComplianceRows.Rg}' AND (((payload::jsonb->>'opened')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'opened')::timestamptz < (:to)::timestamptz) OR ((payload::jsonb->>'closed')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'closed')::timestamptz < (:to)::timestamptz)) ORDER BY COALESCE((payload::jsonb->>'opened')::timestamptz, (payload::jsonb->>'closed')::timestamptz), row_key")

  /** The KYC decisions made from `from` (inclusive) to `to` (exclusive), by when they were made. */
  val kycBetween = query("kyc-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'kind' = '${ComplianceRows.Kyc}' AND (payload::jsonb->>'closed')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'closed')::timestamptz < (:to)::timestamptz ORDER BY (payload::jsonb->>'closed')::timestamptz, row_key")

  def create(ctx: ViewComponentContext) = new ComplianceView


  def between(views: ViewClient, generation: Int, kind: String, from: Instant, to: Instant): Vector[ComplianceRow] =
    Paging.bounded(views.forView(Compliance), if kind == ComplianceRows.Kyc then kycBetween else rgBetween,
      "generation" -> generation.toString, "from" -> from.toString, "to" -> to.toString)
