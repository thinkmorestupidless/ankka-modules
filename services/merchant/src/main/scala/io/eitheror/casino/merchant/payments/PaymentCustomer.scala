package io.eitheror.casino.merchant.payments

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import io.eitheror.casino.merchant.MerchantCodecs
import io.eitheror.casino.merchant.cashier.CustomerFields

import scala.concurrent.duration.FiniteDuration

/**
 * A payment's customer fields (FR-032, research R12): key value under the payment id, beside the
 * payment and never in its journal, kept for the provider's needs until the brand erases the player or
 * the installation's retention passes. Read only to build the gateway's initiate.
 */
final case class PaymentCustomerState(paymentId: String, customerId: String = "", customer: CustomerFields = CustomerFields())

final case class KeepCustomer(customerId: String, customer: CustomerFields, retention: FiniteDuration)

object PaymentCustomerCodecs:
  given Serializer[PaymentCustomerState] = MerchantCodecs.serializer[PaymentCustomerState]("payment-customer")
  given Serializer[KeepCustomer]         = MerchantCodecs.serializer[KeepCustomer]("keep-customer")

import PaymentCustomerCodecs.given

final class PaymentCustomer(context: KeyValueEntityContext) extends KeyValueEntity[PaymentCustomerState]:
  def emptyState: PaymentCustomerState = PaymentCustomerState(context.entityId)

  def keep(cmd: KeepCustomer): Effect[PaymentCustomerState] =
    effects.updateState(PaymentCustomerState(context.entityId, cmd.customerId, cmd.customer)).expireAfter(cmd.retention).thenReplyState

  def erase: Effect[Boolean] =
    if currentState.customerId.isEmpty then effects.reply(false) else effects.deleteEntity().thenReply(_ => true)

  def get: ReadOnlyEffect[PaymentCustomerState] = effects.reply(currentState)

object PaymentCustomer extends KeyValueEntity.Companion[PaymentCustomer, PaymentCustomerState](
      componentId = ComponentId("payment-customer"),
      stateSerializer = PaymentCustomerCodecs.given_Serializer_PaymentCustomerState
    ):
  def create(context: KeyValueEntityContext) = new PaymentCustomer(context)
  val keep  = command("keep")(_.keep)
  val erase = command("erase")(_.erase)
  val get   = query("get")(_.get)
