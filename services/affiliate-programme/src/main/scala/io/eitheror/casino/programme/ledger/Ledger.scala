package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.ProgrammeCodecs.given
import io.eitheror.casino.programme.commission.{FactType, LedgerFact}

import java.time.Instant

/**
 * One ledger fact as every read folds it (research R6): the account it belongs to, the fact, and the
 * payout that claimed it and whether that payout is paid.
 */
final case class LedgerRow(
    affiliate: String,
    brand: String,
    currency: String,
    factId: String,
    kind: FactType,
    amount: Long,
    occurredAt: Instant,
    recordedAt: Instant,
    period: String,
    sourceAffiliate: Option[String] = None,
    settledBy: Option[String] = None,
    paid: Boolean = false,
    reason: Option[String] = None,
    by: Option[String] = None
)

object LedgerRow:
  given Serializer[LedgerRow] = ProgrammeCodecs.serializer[LedgerRow]("ledger-row")

  def of(account: String, f: LedgerFact, reason: Option[String], by: Option[String]): LedgerRow =
    val (affiliate, brand, currency) = Ledger.parts(account)
    LedgerRow(affiliate, brand, currency, f.id, f.kind, f.amount, f.occurredAt, f.recordedAt, f.period, f.sourceAffiliate,
      f.settledBy, reason = reason, by = by)

/** One row per `FactWritten`, stamped by `FactsClaimed`, unstamped by `FactsReleased`, marked by `PayoutPaid` (data-model.md *Views*). */
final class LedgerView extends KeyedView[LedgerRow]:
  import CommissionAccount.*

  def onAccount(event: CommissionAccount.Event, change: Change): Effect =
    val account = change.subject
    event match
      case FactWritten(f, reason, by) => effects.updateRow(Ledger.key(account, f.id), LedgerRow.of(account, f, reason, by))
      case FactsClaimed(p, ids, _, _) =>
        val stamped = ids.toVector.flatMap(id => change.rows.get(Ledger.key(account, id)).map(id -> _)).collect {
          case (id, row) if !row.settledBy.contains(p) => Ledger.key(account, id) -> row.copy(settledBy = Some(p))
        }
        if stamped.isEmpty then effects.ignore() else effects.updateRows(stamped)
      case FactsReleased(p, _) =>
        val held = change.rows.ask(Ledger.ofPayout, "payout" -> p)
        effects.updateRows(held.map(r => Ledger.key(account, r.factId) -> r.copy(settledBy = None)))
      case CommissionAccount.PayoutPaid(p) =>
        val held = change.rows.ask(Ledger.ofPayout, "payout" -> p)
        effects.updateRows(held.map(r => Ledger.key(account, r.factId) -> r.copy(paid = true)))
      case EarningsChanged(_, _) => effects.ignore()

object Ledger extends KeyedView.Companion[LedgerView, LedgerRow](
      componentId = ComponentId("ledger"),
      rowSerializer = LedgerRow.given_Serializer_LedgerRow
    ):
  def key(account: String, factId: String): String = s"$account|$factId"

  /** An account id's affiliate, brand and currency (`<affiliate>/<brand>/<currency>`). */
  def parts(account: String): (String, String, String) = account.split('/') match
    case Array(a, b, c) => (a, b, c)
    case _              => (account, "", "")

  val accounts = source(ChangeSource.eventsOf(CommissionAccount))(_.onAccount)

  /** The facts a payout claimed: its figures, the export, and the stamps a release or payment changes. */
  val ofPayout = query("of-payout")(s"SELECT payload FROM $table WHERE payload::jsonb->>'settledBy' = :payout ORDER BY row_key")

  /**
   * One affiliate's facts at one brand for a period range (`from` and `to` are `yyyy-MM`, inclusive):
   * every figure the portal and the operator read is a fold of these (FR-017).
   */
  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate AND payload::jsonb->>'brand' = :brand " +
      "AND payload::jsonb->>'period' >= :from AND payload::jsonb->>'period' <= :to ORDER BY row_key"
  )

  /** A brand's facts for a period range, for its reports and the usage report (FR-042). */
  val ofBrand = query("of-brand")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand " +
      "AND payload::jsonb->>'period' >= :from AND payload::jsonb->>'period' <= :to ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new LedgerView

/** The folds every read makes of ledger rows: sums by type, per brand and currency, never across currencies. */
object LedgerFold:
  final case class Totals(brand: String, currency: String, byType: Map[FactType, Long]):
    def amount(t: FactType): Long = byType.getOrElse(t, 0L)
    def payable: Long             = FactType.Payable.toVector.map(amount).sum
    /** Bonus costs, chargebacks and the three fees: what stands between GGR and NGR. */
    def deductions: Long =
      Vector(FactType.BonusCost, FactType.Chargeback, FactType.AdminFee, FactType.PaymentFee, FactType.ProviderFee).map(amount).sum
    def ngr: Long = amount(FactType.Ggr) - deductions

  def totals(rows: Iterable[LedgerRow]): Vector[Totals] =
    rows.groupBy(r => (r.brand, r.currency)).toVector.sortBy(_._1).map { case ((b, c), rs) =>
      Totals(b, c, rs.groupMapReduce(_.kind)(_.amount)(_ + _))
    }

  /** Sums by type per period, for the breakdown by month. */
  def byPeriod(rows: Iterable[LedgerRow]): Map[String, Map[FactType, Long]] =
    rows.groupBy(_.period).view.mapValues(_.groupMapReduce(_.kind)(_.amount)(_ + _)).toMap
