package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.{IngestCodecs, IngestRuntime}

import java.time.Duration
import scala.concurrent.duration.*

/**
 * Which sub-brand a cashier ticket or a payment belongs to (FR-009, research R7), under `ticket/<ticket>`
 * or `payment/<paymentId>`: written when a create is answered (the ticket) and when a submit is answered
 * (the payment, and the ticket's gains the payment id). A submit, an abandon or a read under a ticket or a
 * payment no ownership names never reaches `merchant`.
 */
final case class OwnershipState(
    subBrand: String = "",
    requestId: Option[String] = None,
    recordId: String = "",
    ticket: Option[String] = None,
    paymentId: Option[String] = None
):
  def exists: Boolean = subBrand.nonEmpty

object OwnershipCodecs:
  given Serializer[OwnershipState]         = IngestCodecs.serializer[OwnershipState]("ownership-state")
  given Serializer[Option[OwnershipState]] = IngestCodecs.serializer[Option[OwnershipState]]("ownership-read")

import OwnershipCodecs.given

final class Ownership(@scala.annotation.unused context: KeyValueEntityContext, retention: Duration) extends KeyValueEntity[OwnershipState]:

  def emptyState: OwnershipState = OwnershipState()

  /** The first owner is kept; a later set adds what the first did not know (the ticket's payment id). */
  def set(cmd: OwnershipState): Effect[OwnershipState] =
    val s    = currentState
    val next =
      if !s.exists then cmd
      else s.copy(requestId = s.requestId.orElse(cmd.requestId), ticket = s.ticket.orElse(cmd.ticket),
        paymentId = s.paymentId.orElse(cmd.paymentId))
    if next == s then effects.reply(s)
    else effects.updateState(next).expireAfter(retention.toMillis.millis).thenReplyState

  def read: ReadOnlyEffect[Option[OwnershipState]] = effects.reply(Some(currentState).filter(_.exists))

object Ownership extends KeyValueEntity.Companion[Ownership, OwnershipState](
      componentId = ComponentId("ownership"),
      stateSerializer = OwnershipCodecs.given_Serializer_OwnershipState
    ):
  def create(context: KeyValueEntityContext) = new Ownership(context, IngestRuntime.settings.recordRetention)

  def ofTicket(ticket: String): String     = s"ticket/$ticket"
  def ofPayment(paymentId: String): String = s"payment/$paymentId"

  val set  = command("set")(_.set)
  val read = query("read")(_.read)
