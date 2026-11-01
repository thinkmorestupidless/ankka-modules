package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

/** The account an email belongs to, id the email's keyed digest (research R9): the address itself is never stored. */
final case class EmailIndexState(affiliate: Option[String] = None)

object EmailIndexCodecs:
  given Serializer[EmailIndexState] = ProgrammeCodecs.serializer[EmailIndexState]("email-index")

import com.thinkmorestupidless.ankka.core.Serializers.given

final class EmailIndex(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[EmailIndexState]:
  def emptyState: EmailIndexState = EmailIndexState()

  /** The email for `affiliate`; refused `email_already_registered` when another account holds it. */
  def claim(affiliate: String): Effect[EmailIndexState] =
    currentState.affiliate match
      case Some(`affiliate`) => effects.reply(currentState)
      case Some(_)           => effects.error("email_already_registered", ErrorCode.Conflict)
      case None              => effects.updateState(EmailIndexState(Some(affiliate))).thenReplyState

  /** Frees the email, when `affiliate` holds it (an erasure, a change of address). */
  def release(affiliate: String): Effect[EmailIndexState] =
    if currentState.affiliate.contains(affiliate) then effects.deleteEntity().thenReplyState else effects.reply(currentState)

  def get: ReadOnlyEffect[EmailIndexState] = effects.reply(currentState)

object EmailIndex extends KeyValueEntity.Companion[EmailIndex, EmailIndexState](
      componentId = ComponentId("email-index"),
      stateSerializer = EmailIndexCodecs.given_Serializer_EmailIndexState
    ):
  def create(context: KeyValueEntityContext) = new EmailIndex(context)

  val claim   = command("claim")(_.claim)
  val release = command("release")(_.release)
  val get     = query("get")(_.get)
