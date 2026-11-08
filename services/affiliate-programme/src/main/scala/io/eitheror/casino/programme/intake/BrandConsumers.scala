package io.eitheror.casino.programme.intake

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact}
import io.eitheror.casino.programme.Wiring
import org.slf4j.LoggerFactory

import scala.util.Try

/** A brand's fact on its own topic, to `Intake` on the channel `topic:<slug>`; one this service cannot read is skipped. */
final class BrandConsumer(slug: String, context: ConsumerContext) extends Consumer[Option[AttributionFact], Unit]:
  def onMessage(fact: Option[AttributionFact]): Effect = fact match
    case Some(f) =>
      Intake(IntakeCalls.of(context.componentClient), () => Wiring.clock.get().instant()).handle(f, s"topic:$slug", slug): Unit
      effects.done()
    case None =>
      BrandConsumer.log.warn("an affiliates.attribution fact of {} this service cannot read was skipped (subject {})", slug, messageContext.subject)
      effects.ignore()

object BrandConsumer:
  private val log = LoggerFactory.getLogger(classOf[BrandConsumer])

  /** A fact that does not decode is `None`, skipped and logged, rather than a consumer stuck on it. */
  def lenient[F](facts: Serializer[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    def manifest: String                         = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]   = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

/**
 * The consumer `attribution-<slug>` of project `<slug>`'s `affiliates.attribution` (contracts/inbound.md),
 * from the earliest offset: one per slug of `PROGRAMME_BRANDS`.
 */
final class BrandTopic(val slug: String)
    extends Consumer.Companion[BrandConsumer, Option[AttributionFact], Unit](
      componentId = ComponentId(s"attribution-$slug"),
      source = ChangeSource.fromTopic(slug, AffiliatesAttribution.name, BrandConsumer.lenient(AffiliatesAttribution.serializer),
        StartFrom.Earliest)
    ):
  def create(ctx: ConsumerContext) = new BrandConsumer(slug, ctx)

object BrandConsumers:
  def of(brands: Vector[String]): Vector[BrandTopic] = brands.map(BrandTopic(_))
