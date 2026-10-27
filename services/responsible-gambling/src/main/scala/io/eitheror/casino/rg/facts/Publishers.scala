package io.eitheror.casino.rg.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rg.RgRuntime
import io.eitheror.casino.rg.profile.{ProfileEntity, ProfileEvent}

/** `rg_limit_applied`, `rg_case_opened` and `rg_case_closed` on `casino.players`, keyed by the player (FR-030). */
final class RgPublisher extends Consumer[ProfileEvent, CasinoPlayersFact]:
  def onMessage(event: ProfileEvent): Effect =
    Facts.players(messageContext.subject, messageContext.sequenceNumber, event).fold(effects.ignore())(p =>
      effects.produceAll(Seq(effects.message(p.fact).withKey(p.fact.key).withMetadata(Metadata.empty.set("ce-id", p.id)))))

object RgPublisher extends Consumer.Companion[RgPublisher, ProfileEvent, CasinoPlayersFact](
      ComponentId("rg-publisher"), ChangeSource.eventsOf(ProfileEntity)):
  override val outputSerializer: Option[Serializer[CasinoPlayersFact]] = Some(CasinoPlayers.serializer)
  override val produceTo: Option[String]                               = Some(CasinoPlayers.name)
  override val produces: Option[Publication] = Some(Publication(CasinoPlayers.name, Some(CasinoPlayers.contract), None))
  def create(ctx: ConsumerContext) = new RgPublisher

/** An operator's changes to a profile on `platform.audit` (FR-028), a note or reason named and never written. */
final class AuditPublisher extends AuditConsumer[ProfileEvent]:
  protected def audit(change: ProfileEvent): Option[AuditRecord] =
    Facts.audit(RgRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change).map(_.fact)

object AuditPublisher extends AuditCompanion[AuditPublisher, ProfileEvent]("rg-audit-publisher", ChangeSource.eventsOf(ProfileEntity)):
  def create(ctx: ConsumerContext) = new AuditPublisher
