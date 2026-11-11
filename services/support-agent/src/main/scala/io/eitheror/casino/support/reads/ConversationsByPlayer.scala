package io.eitheror.casino.support.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.SupportCodecs
import io.eitheror.casino.support.conversation.{Conversation, ConversationEvent, EndReason}

import java.time.Instant

/**
 * One conversation's row (data-model.md `ConversationsByPlayer`), from its events: the session, the player by id, where
 * it stands, and how many turns, hand-offs and refusals it had. No message text and no personal value is here.
 */
final case class ConversationRow(
    session: String,
    playerId: String = "",
    status: String = ConversationRow.Open,
    startedAt: Option[Instant] = None,
    lastActivity: Instant = Instant.EPOCH,
    turns: Int = 0,
    handedOff: Int = 0,
    rejections: Int = 0
)

object ConversationRow:
  given Serializer[ConversationRow] = SupportCodecs.row[ConversationRow]("conversation-row")

  val Open      = "open"
  val Awaiting  = "awaiting"
  val Excluded  = "excluded"
  val OnBreak   = "on_break"
  val Forgotten = "forgotten"

  import ConversationEvent.*

  /** The row after `e`, as `Conversation.applyEvent` moves the state. */
  def next(row: ConversationRow, e: ConversationEvent): ConversationRow =
    val r = row.copy(lastActivity = e.at)
    e match
      case s: Started if row.status == Forgotten =>
        ConversationRow(row.session, s.playerId, Open, Some(s.at), s.at)
      case s: Started              => r.copy(playerId = s.playerId, startedAt = Some(s.at))
      case _: TurnTaken            => r.copy(turns = r.turns + 1)
      case _: ApprovalRequested    => r.copy(status = Awaiting)
      case _: Decided              => r.copy(status = Open)
      case _: GuardrailRejected    => r.copy(rejections = r.rejections + 1)
      case _: HandedOff            => r.copy(handedOff = r.handedOff + 1)
      case Ended(EndReason.SelfExcluded, _) => r.copy(status = Excluded)
      case Ended(_: EndReason.OnBreak, _)   => r.copy(status = OnBreak)
      case _: Forgotten            => if r.status == Excluded || r.status == OnBreak then r else r.copy(status = Forgotten)
      case _: ToolCalled           => r

final class ConversationsByPlayerView extends View[ConversationEvent, ConversationRow]:
  def onChange(e: ConversationEvent): Effect =
    val row  = rowState.getOrElse(ConversationRow(updateContext.subject))
    val next = ConversationRow.next(row, e)
    if rowState.contains(next) then effects.ignore() else effects.updateRow(next)

object ConversationsByPlayer extends View.Companion[ConversationsByPlayerView, ConversationEvent, ConversationRow](
      componentId = ComponentId("support-conversations"),
      source = ChangeSource.eventsOf(Conversation),
      rowSerializer = ConversationRow.given_Serializer_ConversationRow
    ):
  def create(ctx: ViewComponentContext) = new ConversationsByPlayerView

  /** The operator's page (contracts/routes.md): 50 rows. */
  val PageSize = 50

  private def at(field: String) = SqlFragment.raw(s"(payload::jsonb->>'$field')::timestamptz")
  private val newest            = at("lastActivity") ++ SqlFragment.raw(" DESC, payload::jsonb->>'session' DESC")
  private def status(s: String*) = jsonText("status") ++ sql" IN (" ++ s.map(v => sql"$v").reduce(_ ++ SqlFragment.raw(", ") ++ _) ++ SqlFragment.raw(")")

  /** The conversations, newest activity first, of one player when `player` names one, from `offset`. */
  def page(view: ViewQueries[ConversationRow], player: Option[String], offset: Int): Vector[ConversationRow] =
    val condition = player.fold(sql"1 = 1")(p => jsonText("playerId") ++ sql" = $p")
    val from      = math.max(0, offset)
    view.ordered(condition, newest, from + PageSize).drop(from)

  /** The status route's counts: open (not awaiting), awaiting, and active within the day before `now`. */
  final case class Counts(open: Long, awaiting: Long, lastDay: Long)

  def counts(view: ViewQueries[ConversationRow], now: Instant): Counts =
    Counts(
      view.count(status(ConversationRow.Open)),
      view.count(status(ConversationRow.Awaiting)),
      view.count(at("lastActivity") ++ sql" >= ${now.minusSeconds(86400).toString}::timestamptz")
    )
