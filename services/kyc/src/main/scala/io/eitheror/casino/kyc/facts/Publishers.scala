package io.eitheror.casino.kyc.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.KycRuntime
import io.eitheror.casino.kyc.policy.{PolicyChanged, PolicyEntity}
import io.eitheror.casino.kyc.record.{KycEvent, KycRecord}

/** The KYC families on `casino.players`, keyed by the player (FR-030). */
final class KycPublisher extends Consumer[KycEvent, CasinoPlayersFact]:
  def onMessage(event: KycEvent): Effect =
    Facts.players(messageContext.subject, messageContext.sequenceNumber, event).fold(effects.ignore())(p =>
      effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Metadata.empty.set("ce-id", p.id)))))

object KycPublisher extends Consumer.Companion[KycPublisher, KycEvent, CasinoPlayersFact](
      ComponentId("kyc-publisher"), ChangeSource.eventsOf(KycRecord)):
  override val outputSerializer: Option[Serializer[CasinoPlayersFact]] = Some(CasinoPlayers.serializer)
  override val produceTo: Option[String]                               = Some(CasinoPlayers.name)
  override val produces: Option[Publication] = Some(Publication(CasinoPlayers.name, Some(CasinoPlayers.contract), None))
  def create(ctx: ConsumerContext) = new KycPublisher

/** The record's audited changes on `platform.audit` (FR-014, FR-037), a note or reason named and never written. */
final class AuditPublisher extends AuditConsumer[KycEvent]:
  protected def audit(change: KycEvent): Option[AuditRecord] =
    Facts.audit(KycRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change).map(_.fact)

object AuditPublisher extends AuditCompanion[AuditPublisher, KycEvent]("kyc-audit-publisher", ChangeSource.eventsOf(KycRecord)):
  def create(ctx: ConsumerContext) = new AuditPublisher

/** Every change to the brand's policy on `platform.audit`, each field before and after (FR-028). */
final class PolicyAuditPublisher extends AuditConsumer[PolicyChanged]:
  protected def audit(change: PolicyChanged): Option[AuditRecord] =
    Some(Facts.policyAudit(messageContext.subject, messageContext.sequenceNumber, change).fact)

object PolicyAuditPublisher
    extends AuditCompanion[PolicyAuditPublisher, PolicyChanged]("kyc-policy-audit-publisher", ChangeSource.eventsOf(PolicyEntity)):
  def create(ctx: ConsumerContext) = new PolicyAuditPublisher
