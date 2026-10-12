package io.eitheror.casino.gameprovider.tickets

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.core.Serializers.given

import java.time.Instant

/**
 * A launch ticket and the session ticket it becomes (glossary; FR-008, FR-010, research R5): one key
 * value entity per token, `issued` then `checked`, never expiring. Checking is one command, so the
 * original's read-then-write race between two checks of one token cannot happen here.
 */
final case class TicketState(
    token: String,
    status: String = TicketState.Unissued,
    provider: String = "",
    playerId: String = "",
    gameCode: String = "",
    gameCategory: Option[String] = None,
    sessionId: String = "",
    currency: String = "",
    freeRoundsGrant: Option[String] = None,
    funMode: Boolean = false,
    issuedAt: Option[Instant] = None,
    checkedAt: Option[Instant] = None
):
  def isChecked: Boolean = status == TicketState.Checked

object TicketState:
  val Unissued = "none"
  val Issued  = "issued"
  val Checked = "checked"

final case class IssueTicket(provider: String, playerId: String, gameCode: String, gameCategory: Option[String],
    sessionId: String, currency: String, freeRoundsGrant: Option[String], funMode: Boolean, at: Instant)

final case class CheckTicket(gameCode: Option[String], at: Instant)

/** The check's answer: accepted with the ticket, or refused (`session_expired`, FR-010). */
final case class TicketCheck(accepted: Boolean, ticket: TicketState)

object TicketCodecs:
  given Serializer[TicketState] = Codecs.serializer[TicketState]("ticket-state")
  given Serializer[IssueTicket] = Codecs.serializer[IssueTicket]("issue-ticket")
  given Serializer[CheckTicket] = Codecs.serializer[CheckTicket]("check-ticket")
  given Serializer[TicketCheck] = Codecs.serializer[TicketCheck]("ticket-check")

import TicketCodecs.given

final class Ticket(context: KeyValueEntityContext) extends KeyValueEntity[TicketState]:

  def emptyState: TicketState = TicketState(context.entityId)

  /** Issues the ticket once; issuing a token already issued is refused, since tokens are minted fresh. */
  def issue(cmd: IssueTicket): Effect[TicketState] =
    if currentState.status != TicketState.Unissued then effects.error(s"ticket ${context.entityId} already issued", com.thinkmorestupidless.ankka.core.ErrorCode.Conflict)
    else
      effects.updateState(TicketState(context.entityId, TicketState.Issued, cmd.provider, cmd.playerId, cmd.gameCode,
        cmd.gameCategory, cmd.sessionId, cmd.currency, cmd.freeRoundsGrant, cmd.funMode, Some(cmd.at))).thenReplyState

  /**
   * The provider's `check`: accepted once, for the ticket's own game (an absent game code matches), and
   * the ticket becomes the session ticket; unknown, already checked or another game is refused.
   */
  def check(cmd: CheckTicket): Effect[TicketCheck] =
    val s = currentState
    val gameMatches = cmd.gameCode.forall(_ == s.gameCode)
    if s.status != TicketState.Issued || !gameMatches then effects.reply(TicketCheck(accepted = false, s))
    else
      val checked = s.copy(status = TicketState.Checked, checkedAt = Some(cmd.at))
      effects.updateState(checked).thenReply(_ => TicketCheck(accepted = true, checked))

  /** The game session `players` opened once the provider launched the game (FR-009); only while unchecked. */
  def attachSession(sessionId: String): Effect[TicketState] =
    if currentState.status != TicketState.Issued then effects.reply(currentState)
    else effects.updateState(currentState.copy(sessionId = sessionId)).thenReplyState

  /** A launch that failed discards its ticket, so it can never be checked (spec Edge Cases). */
  def discard: Effect[Boolean] =
    if currentState.status != TicketState.Issued then effects.reply(false) else effects.deleteEntity().thenReply(_ => true)

  def get: ReadOnlyEffect[TicketState] = effects.reply(currentState)

object Ticket extends KeyValueEntity.Companion[Ticket, TicketState](
      componentId = ComponentId("ticket"),
      stateSerializer = TicketCodecs.given_Serializer_TicketState
    ):
  def create(context: KeyValueEntityContext) = new Ticket(context)

  val issue = command("issue")(_.issue)
  val check         = command("check")(_.check)
  val attachSession = command("attach-session")(_.attachSession)
  val discard       = command("discard")(_.discard)
  val get           = query("get")(_.get)
