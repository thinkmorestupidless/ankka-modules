package io.eitheror.casino.payouts.queue

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.payouts.PayoutsCodecs
import io.eitheror.casino.payouts.player.Iban
import io.eitheror.casino.payouts.request.{RequestEvent, RequestState, Status, PayoutStatus, WithdrawalRequest}

import java.time.Instant

/**
 * One request's row in the brand's queue and the player's list (data-model.md *WithdrawalQueue*). No
 * `Personal` value is kept: the IBAN's last four is not one, and the player id is the subject's.
 */
final case class QueueRow(
    requestId: String,
    playerId: String = "",
    kyc: String = "",
    amount: Long = 0L,
    currency: String = "",
    terminalName: String = "",
    method: String = "",
    terminalId: String = "",
    destinationLastFour: Option[String] = None,
    createdAt: Instant = Instant.EPOCH,
    initiatedAt: Option[Instant] = None,
    status: String = Status.PendingReview.wire,
    payoutStatus: String = PayoutStatus.None.wire,
    amountBand: String = ""
)

object QueueRow:
  given Serializer[QueueRow] = PayoutsCodecs.row[QueueRow]("queue-row")

  /** The band of an amount in major units of its currency (two places for every currency the brand pays in). */
  def band(amount: Long): String =
    if amount < 50000L then "lt500" else if amount <= 500000L then "500to5000" else "gt5000"

  def of(s: RequestState): QueueRow =
    QueueRow(
      requestId = s.requestId,
      playerId = s.playerId,
      kyc = s.kyc,
      amount = s.amount,
      currency = s.currency,
      terminalName = s.terminal.fold("")(_.name),
      method = s.terminal.fold("")(_.scheme),
      terminalId = s.terminal.fold("")(_.id),
      destinationLastFour = s.payee.iban.map(i => Iban.lastFour(i.reveal)),
      createdAt = s.createdAt,
      initiatedAt = s.initiatedAt,
      status = s.status.wire,
      payoutStatus = s.payoutStatus.wire,
      amountBand = band(s.amount)
    )

/**
 * The request's row, rebuilt from the request's own rule (`WithdrawalRequest.applied`) over what the row
 * knows. Erasure keeps the row with its last four gone. An event before `Opened` writes nothing.
 */
final class WithdrawalQueueView extends View[RequestEvent, QueueRow]:
  def onChange(event: RequestEvent): Effect =
    val known = rowState.map(WithdrawalQueue.state)
    val next  = WithdrawalRequest.applied(known.getOrElse(RequestState()), event)
    if !next.opened then effects.ignore()
    else
      val row = QueueRow.of(next)
      if rowState.contains(row) then effects.ignore() else effects.updateRow(row)

object WithdrawalQueue extends View.Companion[WithdrawalQueueView, RequestEvent, QueueRow](
      componentId = ComponentId("withdrawal-queue"),
      source = ChangeSource.eventsOf(WithdrawalRequest),
      rowSerializer = QueueRow.given_Serializer_QueueRow
    ):
  def create(ctx: ViewComponentContext) = new WithdrawalQueueView

  /** What a row says of its request, enough for the request's rule to apply the next event to it. */
  private[queue] def state(r: QueueRow): RequestState =
    RequestState(opened = true, requestId = r.requestId, playerId = r.playerId, amount = r.amount, currency = r.currency, kyc = r.kyc,
      status = Status.from(r.status).getOrElse(Status.PendingReview),
      payoutStatus = PayoutStatus.values.find(_.wire == r.payoutStatus).getOrElse(PayoutStatus.None),
      createdAt = r.createdAt, initiatedAt = r.initiatedAt,
      terminal = Some(io.eitheror.casino.payouts.request.TerminalRef(r.terminalId, "", r.terminalName, "", r.method, true)),
      payee = io.eitheror.casino.payouts.request.Payee(iban = r.destinationLastFour.map(io.eitheror.casino.contract.personal.Personal(_))))

  /** The queue's filters (contracts/routes.md): each is a condition; none is every row. */
  final case class Filter(
      status: Option[String] = Some(Status.PendingReview.wire),
      payoutStatus: Option[String] = None,
      kyc: Option[String] = None,
      method: Option[String] = None,
      band: Option[String] = None,
      player: Option[String] = None,
      staleBefore: Option[Instant] = None
  ):
    /**
     * `player` is one player id, or several separated by commas (spec 030 Y2: the backoffice resolves a name
     * or an email to ids through players' search first); at most `MaxPlayers` are taken.
     */
    private def players(raw: String): SqlFragment =
      val ids = raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).distinct.take(MaxPlayers).toVector
      if ids.isEmpty then sql"1 = 0"
      else
        SqlFragment.raw("(") ++ ids.map(p => jsonText("playerId") ++ sql" = $p").reduce((a, b) => a ++ SqlFragment.raw(" OR ") ++ b) ++
          SqlFragment.raw(")")

    def condition: SqlFragment =
      val parts = Vector(
        status.map(s => jsonText("status") ++ sql" = $s"),
        payoutStatus.map(s => jsonText("payoutStatus") ++ sql" = $s"),
        kyc.map(k => jsonText("kyc") ++ sql" = $k"),
        method.map(m => jsonText("method") ++ sql" = $m"),
        band.map(b => jsonText("amountBand") ++ sql" = $b"),
        player.map(players),
        staleBefore.map(t => SqlFragment.raw("(payload::jsonb->>'initiatedAt')::timestamptz") ++ sql" < CAST(${t.toString} AS timestamptz)")
      ).flatten
      if parts.isEmpty then sql"1 = 1" else parts.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)

  /** One page of a filter, newest first or oldest first, and how many rows the filter holds. */
  final case class Page(rows: Vector[QueueRow], total: Long, page: Int, pageSize: Int)

  val MaxPageSize = 200

  /** The most player ids one filter names. */
  val MaxPlayers = 200

  // The request id breaks a tie, so a page is the same slice each time it is asked for.
  private val Newest = SqlFragment.raw("(payload::jsonb->>'createdAt')::timestamptz DESC, payload::jsonb->>'requestId' DESC")
  private val Oldest = SqlFragment.raw("(payload::jsonb->>'createdAt')::timestamptz ASC, payload::jsonb->>'requestId' ASC")

  def page(queue: ViewQueries[QueueRow], filter: Filter, page: Int, pageSize: Int, oldest: Boolean = false): Page =
    val p    = math.max(1, page)
    val size = math.min(MaxPageSize, math.max(1, pageSize))
    val upTo = queue.ordered(filter.condition, if oldest then Oldest else Newest, p * size)
    Page(upTo.drop((p - 1) * size).take(size), queue.count(filter.condition), p, size)

  /** The brand's two queues of work (spec 030 Y2): the requests pending review, and the approved ones whose payment needs review. */
  final case class Counts(pendingReview: Long, needsReview: Long)

  val PendingReview: Filter = Filter()
  val NeedsReview: Filter   = Filter(status = Some(Status.Approved.wire), payoutStatus = Some(PayoutStatus.NeedsReview.wire))

  def counts(queue: ViewQueries[QueueRow]): Counts =
    Counts(queue.count(PendingReview.condition), queue.count(NeedsReview.condition))

  /** One page of the player's requests in every state, newest first (spec 030 Y4). */
  def ofPlayer(queue: ViewQueries[QueueRow], playerId: String, page: Int, pageSize: Int): Page =
    this.page(queue, Filter(status = None, player = Some(playerId)), page, pageSize)

  /** The player's requests, newest first. */
  def byPlayer(queue: ViewQueries[QueueRow], playerId: String, limit: Int = 1000): Vector[QueueRow] =
    queue.ordered(Filter(status = None, player = Some(playerId)).condition, Newest, limit)
