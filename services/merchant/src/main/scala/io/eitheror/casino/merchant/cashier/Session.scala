package io.eitheror.casino.merchant.cashier

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import io.eitheror.casino.merchant.MerchantCodecs
import io.eitheror.casino.merchant.routing.ResolvedFrom

import java.time.Instant
import scala.concurrent.duration.*

/** A terminal a session offers (FR-014): a credential by its name, or a terminal by its id. */
final case class OfferedTerminal(terminalEntityId: String, name: String, scheme: String, gateway: Option[String],
    supportedCurrencies: Vector[String], credentialId: Option[String])

/**
 * A cashier session (FR-013, FR-014), key value under its token, expiring with the payment's deadline:
 * everything the submit needs, the customer fields as `Personal` values (research R12), and once
 * submitted the payment it made, so a second submit is answered from that payment and makes none
 * (spec correction 2).
 */
final case class SessionState(
    token: String,
    subBrandId: String = "",
    brand: Option[String] = None,
    transactionId: String = "",
    amount: Long = 0,
    currency: String = "",
    country: String = "",
    customerId: String = "",
    redirectUrl: String = "",
    customer: CustomerFields = CustomerFields(),
    offered: Vector[OfferedTerminal] = Vector.empty,
    resolvedFrom: Option[ResolvedFrom] = None,
    createdAt: Option[Instant] = None,
    deadline: Option[Instant] = None,
    paymentId: Option[String] = None
):
  def exists: Boolean = createdAt.isDefined

final case class OpenSession(state: SessionState, at: Instant)

object SessionCodecs:
  given Serializer[SessionState] = MerchantCodecs.serializer[SessionState]("session-state")
  given Serializer[OpenSession]  = MerchantCodecs.serializer[OpenSession]("open-session")

import SessionCodecs.given

final class Session(context: KeyValueEntityContext) extends KeyValueEntity[SessionState]:

  def emptyState: SessionState = SessionState(context.entityId)

  def open(cmd: OpenSession): Effect[SessionState] =
    if currentState.exists then effects.error("session exists", ErrorCode.Conflict)
    else
      val ttl = cmd.state.deadline.map(d => math.max(1L, java.time.Duration.between(cmd.at, d).toMillis).millis).getOrElse(30.minutes)
      effects.updateState(cmd.state.copy(token = context.entityId, createdAt = Some(cmd.at))).expireAfter(ttl).thenReplyState

  /**
   * Claims the session for a payment id: the first claim wins and every later one is answered with
   * it, so two submits racing make one payment.
   */
  def claim(paymentId: String): Effect[String] =
    if !currentState.exists then effects.error("SESSION_NOT_FOUND", ErrorCode.NotFound)
    else
      currentState.paymentId match
        case Some(first) => effects.reply(first)
        case None        => effects.updateState(currentState.copy(paymentId = Some(paymentId))).thenReply(_ => paymentId)

  /** Lets the session be submitted again after the gateway refused its payment (the player may choose again). */
  def release(paymentId: String): Effect[Boolean] =
    if currentState.paymentId.contains(paymentId) then effects.updateState(currentState.copy(paymentId = None)).thenReply(_ => true)
    else effects.reply(false)

  /** The erasure handler's deletion (FR-032). */
  def erase: Effect[Boolean] =
    if !currentState.exists then effects.reply(false) else effects.deleteEntity().thenReply(_ => true)

  def get: ReadOnlyEffect[SessionState] = effects.reply(currentState)

object Session extends KeyValueEntity.Companion[Session, SessionState](
      componentId = ComponentId("session"),
      stateSerializer = SessionCodecs.given_Serializer_SessionState
    ):
  def create(context: KeyValueEntityContext) = new Session(context)

  val open    = command("open")(_.open)
  val claim   = command("claim")(_.claim)
  val release = command("release")(_.release)
  val erase   = command("erase")(_.erase)
  val get     = query("get")(_.get)

/** The sessions of a customer, for the erasure handler (FR-032): the token and the customer id, nothing personal. */
final case class SessionRow(token: String, customerId: String)

final class SessionsByCustomerView extends View[SessionState, SessionRow]:
  def onChange(state: SessionState): Effect =
    if state.exists then effects.updateRow(SessionRow(updateContext.subject, state.customerId)) else effects.ignore()

object SessionsByCustomer extends View.Companion[SessionsByCustomerView, SessionState, SessionRow](
      componentId = ComponentId("sessions-by-customer"),
      source = ChangeSource.stateOf(Session),
      rowSerializer = MerchantCodecs.serializer[SessionRow]("session-row")
    ):
  def create(ctx: ViewComponentContext) = new SessionsByCustomerView
