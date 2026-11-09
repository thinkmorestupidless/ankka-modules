package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.{Settings, Wiring}
import io.eitheror.casino.audit.stored.Source
import io.eitheror.casino.contract.facts.{PlatformAudit, PlatformModules}

/**
 * One source's messages to [[Ingest]] (contracts/ingest.md, "Sources"). Read as bytes, so a message
 * that does not decode is quarantined rather than leaving the consumer stuck on it.
 */
final class RecordsConsumer(source: Source, context: ConsumerContext) extends Consumer[Array[Byte], Unit]:
  def onMessage(bytes: Array[Byte]): Effect =
    Ingest.record(source, bytes, Wiring.clock.get().instant(), IngestCalls.of(context.componentClient), Wiring.requiredKeys): Unit
    effects.done()

final class ModulesConsumer(brand: String, context: ConsumerContext) extends Consumer[Array[Byte], Unit]:
  def onMessage(bytes: Array[Byte]): Effect =
    Ingest.module(brand, bytes, Wiring.clock.get().instant(), IngestCalls.of(context.componentClient), Wiring.requiredKeys): Unit
    effects.done()

object Consumers:
  /**
   * Where a consumer group starts (research R14): the earliest at generation 1; a raised generation,
   * a new group, at the restore point. Re-reading changes nothing the store already holds.
   */
  def startFrom(settings: Settings): StartFrom =
    settings.readFrom.filter(_ => settings.readGeneration > 1).fold(StartFrom.Earliest)(StartFrom.At(_))

  /** `records-<slug>` per brand, `records-payments`, and `modules-<slug>` per brand. */
  def of(settings: Settings): Vector[Consumer.Companion[?, Array[Byte], Unit]] =
    settings.brands.map(RecordsTopic(_, settings)) ++ Vector(PaymentsTopic(settings)) ++ settings.brands.map(ModulesTopic(_, settings))

/** A brand's `platform.audit`, read under the grant `core` makes `service:reasoner/audit`. */
final class RecordsTopic(val slug: String, settings: Settings)
    extends Consumer.Companion[RecordsConsumer, Array[Byte], Unit](
      componentId = ComponentId(s"records-$slug"),
      source = ChangeSource.fromTopic(slug, PlatformAudit.name, Serializer.bytes, Consumers.startFrom(settings))
    ):
  override def version: Option[Int] = Some(settings.readGeneration)
  def create(ctx: ConsumerContext) = new RecordsConsumer(Source.Brand(slug), ctx)

/** `payments`' `platform.audit`, read under the grant `shared/payments/manifest.json` makes. */
final class PaymentsTopic(settings: Settings)
    extends Consumer.Companion[RecordsConsumer, Array[Byte], Unit](
      componentId = ComponentId("records-payments"),
      source = ChangeSource.fromTopic("payments", PlatformAudit.name, Serializer.bytes, Consumers.startFrom(settings))
    ):
  override def version: Option[Int] = Some(settings.readGeneration)
  def create(ctx: ConsumerContext) = new RecordsConsumer(Source.Payments, ctx)

/** A brand's `platform.modules`, the vendor agent's changes to the brand's modules. */
final class ModulesTopic(val slug: String, settings: Settings)
    extends Consumer.Companion[ModulesConsumer, Array[Byte], Unit](
      componentId = ComponentId(s"modules-$slug"),
      source = ChangeSource.fromTopic(slug, PlatformModules.name, Serializer.bytes, Consumers.startFrom(settings))
    ):
  override def version: Option[Int] = Some(settings.readGeneration)
  def create(ctx: ConsumerContext) = new ModulesConsumer(slug, ctx)
