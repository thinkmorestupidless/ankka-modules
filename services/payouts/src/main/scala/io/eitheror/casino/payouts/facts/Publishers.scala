package io.eitheror.casino.payouts.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.payouts.PayoutsRuntime
import io.eitheror.casino.payouts.limits.{LimitsEvent, WithdrawalLimits}
import io.eitheror.casino.payouts.player.{PlayerEvent, PlayerPayouts}
import io.eitheror.casino.payouts.request.{RequestEvent, RequestState, WithdrawalRequest}

/** The request as it stands, which the events after `Opened` need for the player, the amount and the terminal. */
private def requestOf(client: ComponentClient, requestId: String): RequestState =
  client.forEventSourcedEntity(EntityId(requestId)).call(WithdrawalRequest.get).invoke()

/** The withdrawal families on `casino.withdrawals`, keyed by the player (contracts/facts.md). */
final class WithdrawalPublisher(client: ComponentClient) extends Consumer[RequestEvent, CasinoWithdrawalsFact]:
  def onMessage(event: RequestEvent): Effect =
    val id = messageContext.subject
    val published = event match
      case _: RequestEvent.Opened | _: RequestEvent.Approved | _: RequestEvent.Completed | _: RequestEvent.Cancelled |
          _: RequestEvent.Rejected | _: RequestEvent.Failed | _: RequestEvent.Reversed =>
        Facts.withdrawal(id, messageContext.sequenceNumber, event, requestOf(client, id))
      case _ => None
    published.fold(effects.ignore())(p =>
      effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Metadata.empty.set("ce-id", p.id)))))

object WithdrawalPublisher extends Consumer.Companion[WithdrawalPublisher, RequestEvent, CasinoWithdrawalsFact](
      ComponentId("withdrawal-publisher"), ChangeSource.eventsOf(WithdrawalRequest)):
  override val outputSerializer: Option[Serializer[CasinoWithdrawalsFact]] = Some(CasinoWithdrawals.serializer)
  override val produceTo: Option[String]                                   = Some(CasinoWithdrawals.name)
  override val produces: Option[Publication] = Some(Publication(CasinoWithdrawals.name, Some(CasinoWithdrawals.contract), None))
  def create(ctx: ConsumerContext) = new WithdrawalPublisher(ctx.componentClient)

/** Every audited change of a request on `platform.audit`, naming its actor; a note is named, never written. */
final class AuditPublisher(client: ComponentClient) extends AuditConsumer[RequestEvent]:
  protected def audit(change: RequestEvent): Option[AuditRecord] =
    val id = messageContext.subject
    val state = change match
      case o: RequestEvent.Opened => RequestState(requestId = o.requestId, playerId = o.playerId, currency = o.currency)
      case _                      => requestOf(client, id)
    Facts.requestAudit(PayoutsRuntime.settings.brand, id, messageContext.sequenceNumber, change, state).map(_.fact)

object AuditPublisher extends AuditCompanion[AuditPublisher, RequestEvent]("payouts-audit-publisher", ChangeSource.eventsOf(WithdrawalRequest)):
  def create(ctx: ConsumerContext) = new AuditPublisher(ctx.componentClient)

/** A player's destination changes on `platform.audit`: the destination's id, never its holder or IBAN. */
final class PlayerAuditPublisher extends AuditConsumer[PlayerEvent]:
  protected def audit(change: PlayerEvent): Option[AuditRecord] =
    Facts.playerAudit(PayoutsRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change).map(_.fact)

object PlayerAuditPublisher
    extends AuditCompanion[PlayerAuditPublisher, PlayerEvent]("payouts-player-audit-publisher", ChangeSource.eventsOf(PlayerPayouts)):
  def create(ctx: ConsumerContext) = new PlayerAuditPublisher

/** Every change to the brand's withdrawal limits on `platform.audit`, each limit before and after. */
final class ConfigAuditPublisher extends AuditConsumer[LimitsEvent]:
  protected def audit(change: LimitsEvent): Option[AuditRecord] = change match
    case e: LimitsEvent.LimitsSet => Some(Facts.limitsAudit(PayoutsRuntime.settings.brand, messageContext.sequenceNumber, e).fact)

object ConfigAuditPublisher
    extends AuditCompanion[ConfigAuditPublisher, LimitsEvent]("payouts-config-audit-publisher", ChangeSource.eventsOf(WithdrawalLimits)):
  def create(ctx: ConsumerContext) = new ConfigAuditPublisher
