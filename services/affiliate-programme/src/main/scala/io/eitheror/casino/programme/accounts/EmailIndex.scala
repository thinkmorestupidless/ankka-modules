package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant
import scala.concurrent.duration.*

/** The account an email belongs to, id the email's keyed digest (research R9): the address itself is never stored. */
final case class EmailIndexState(affiliate: Option[String] = None, claimedAt: Option[Instant] = None)

/**
 * A claim of the email for `affiliate` at `at`. `orphan` is a holder the caller found with no account: it
 * is taken over only once its claim is older than an enrolment takes, so an enrolment still running (a
 * form sent twice) keeps its claim and the second is refused.
 */
final case class ClaimEmail(affiliate: String, at: Instant, orphan: Option[String] = None)

object EmailIndexCodecs:
  given Serializer[EmailIndexState] = ProgrammeCodecs.serializer[EmailIndexState]("email-index")
  given Serializer[ClaimEmail]      = ProgrammeCodecs.serializer[ClaimEmail]("claim-email")

import EmailIndexCodecs.given

import com.thinkmorestupidless.ankka.core.Serializers.given

final class EmailIndex(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[EmailIndexState]:
  def emptyState: EmailIndexState = EmailIndexState()

  /** The email for `affiliate`; refused `email_already_registered` when another account holds it. */
  def claim(c: ClaimEmail): Effect[EmailIndexState] =
    val stale = currentState.claimedAt.forall(_.plusMillis(EmailIndex.Enrolling.toMillis).isBefore(c.at))
    currentState.affiliate match
      case Some(c.affiliate)                                  => effects.reply(currentState)
      case Some(h) if !(c.orphan.contains(h) && stale)        => effects.error("email_already_registered", ErrorCode.Conflict)
      case _ => effects.updateState(EmailIndexState(Some(c.affiliate), Some(c.at))).thenReplyState

  /** Frees the email, when `affiliate` holds it (an erasure, a change of address). */
  def release(affiliate: String): Effect[EmailIndexState] =
    if currentState.affiliate.contains(affiliate) then effects.deleteEntity().thenReplyState else effects.reply(currentState)

  def get: ReadOnlyEffect[EmailIndexState] = effects.reply(currentState)

object EmailIndex extends KeyValueEntity.Companion[EmailIndex, EmailIndexState](
      componentId = ComponentId("email-index"),
      stateSerializer = EmailIndexCodecs.given_Serializer_EmailIndexState
    ):
  /** Longer than an enrolment takes from its claim to its account: a younger claim with no account is one still running. */
  val Enrolling: FiniteDuration = 5.minutes

  def create(context: KeyValueEntityContext) = new EmailIndex(context)

  val claim   = command("claim")(_.claim)
  val release = command("release")(_.release)
  val get     = query("get")(_.get)
