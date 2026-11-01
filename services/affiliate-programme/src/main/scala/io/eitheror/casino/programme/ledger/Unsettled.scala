package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

/**
 * An account's unclaimed payable facts, counted by period (data-model.md *Views*): what a close pages
 * through. `earliest` is the first period with any, so `yyyy-MM` order finds the accounts a close owes.
 */
final case class UnsettledRow(affiliate: String = "", brand: String = "", currency: String = "", open: Map[String, Int] = Map.empty,
    earliest: Option[String] = None):
  def account: String = s"$affiliate/$brand/$currency"

  def adjusted(by: Map[String, Int], sign: Int): UnsettledRow =
    val next = by.foldLeft(open) { case (m, (p, n)) => m.updated(p, m.getOrElse(p, 0) + sign * n) }.filter(_._2 > 0)
    copy(open = next, earliest = next.keys.minOption)

object UnsettledRow:
  given Serializer[UnsettledRow] = ProgrammeCodecs.serializer[UnsettledRow]("unsettled-row")

  def next(row: UnsettledRow, event: CommissionAccount.Event): UnsettledRow = event match
    case CommissionAccount.FactWritten(f, _, _) if f.kind.payable && f.settledBy.isEmpty => row.adjusted(Map(f.period -> 1), 1)
    case CommissionAccount.FactsClaimed(_, _, _, newly)                                 => row.adjusted(newly, -1)
    case CommissionAccount.FactsReleased(_, periods)                                    => row.adjusted(periods, 1)
    case _                                                                              => row

final class UnsettledView extends View[CommissionAccount.Event, UnsettledRow]:
  def onChange(event: CommissionAccount.Event): Effect =
    val row  = rowState.getOrElse {
      val (a, b, c) = Ledger.parts(updateContext.subject)
      UnsettledRow(a, b, c)
    }
    val next = UnsettledRow.next(row, event)
    if rowState.isDefined && next == row then effects.ignore() else effects.updateRow(next)

object Unsettled extends View.Companion[UnsettledView, CommissionAccount.Event, UnsettledRow](
      componentId = ComponentId("unsettled"),
      source = ChangeSource.eventsOf(CommissionAccount),
      rowSerializer = UnsettledRow.given_Serializer_UnsettledRow
    ):
  /** Accounts at a brand with an unclaimed payable fact in or before `period`, 50 at a time after the key `after`. */
  val owed = query("owed")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'earliest' <= :period " +
      "AND row_key > :after ORDER BY row_key LIMIT 50"
  )

  def create(ctx: ViewComponentContext) = new UnsettledView
