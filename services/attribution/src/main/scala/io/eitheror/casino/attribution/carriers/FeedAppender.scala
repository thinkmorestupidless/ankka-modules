package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.inputs.Lenient
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact}
import org.slf4j.LoggerFactory

/**
 * The brand's own topic into each network's feed (research R14): every fact once, numbered, in the
 * topic's order per key. Read from the start, so a feed added later holds every fact already published.
 */
final class FeedAppender(context: ConsumerContext) extends Consumer[Option[AttributionFact], Unit]:
  def onMessage(fact: Option[AttributionFact]): Effect = fact match
    case Some(f) =>
      context.componentClient.forEventSourcedEntity(EntityId(f.network)).call(NetworkFeed.append).invoke(FeedFact.of(f)): Unit
      effects.done()
    case None =>
      FeedAppender.log.warn("an affiliates.attribution fact this service cannot read was skipped (subject {})", messageContext.subject)
      effects.ignore()

object FeedAppender extends Consumer.Companion[FeedAppender, Option[AttributionFact], Unit](
      componentId = ComponentId("feed-appender"),
      source = ChangeSource.fromTopic(AffiliatesAttribution.name, Lenient.serializer(AffiliatesAttribution.serializer),
        StartFrom.Earliest, TopicOptions(Some(AffiliatesAttribution.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[FeedAppender])

  def create(ctx: ConsumerContext) = new FeedAppender(ctx)
