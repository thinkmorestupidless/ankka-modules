package io.eitheror.casino.exchange.facts

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.exchange.ExchangeEvent
import com.thinkmorestupidless.predictions.exchange.components.ExchangeEntity
import io.eitheror.casino.exchange.{ExchangeRuntime, Json}
import io.eitheror.casino.exchange.ports.{Http, Reply}

import java.time.{Duration, Instant}

/** When an outcome's price was last observed: the throttle's state, one per market and outcome. */
final case class Observed(at: Option[Instant] = None)

object Observed:
  given Serializer[Observed] = Codecs.serializer[Observed]("exchange-observed")
  given Serializer[Instant]  = Codecs.serializer[Instant]("exchange-observed-at")

  /** At most one observation an outcome a minute (FR-007), by the trades' own times so a replay decides the same. */
  val Every: Duration = Duration.ofMinutes(1)

  def due(last: Option[Instant], at: Instant): Boolean = last.forall(l => !at.isBefore(l.plus(Every)))

final class ObservedEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Observed]:
  def emptyState: Observed = Observed()

  /** Claims an observation at `at`: true, and the time kept, when a minute has passed since the last. */
  def claim(at: Instant): Effect[Boolean] =
    if Observed.due(currentState.at, at) then effects.updateState(Observed(Some(at))).thenReply(_ => true) else effects.reply(false)

object ObservedEntity extends KeyValueEntity.Companion[ObservedEntity, Observed](ComponentId("exchange-observed"), Observed.given_Serializer_Observed):
  import Observed.given_Serializer_Instant
  import com.thinkmorestupidless.ankka.core.Serializers.given
  def create(context: KeyValueEntityContext) = new ObservedEntity(context)
  val claim = command("claim")(_.claim)

/**
 * A trade's last prices observed by the reasoning service the brand's values name (FR-007,
 * ankka-reasoning's `POST /markets/{market}/price-observations`), an outcome at most once a minute.
 * Registered only when the values name one. A service that cannot be reached or answers 5xx throws, so
 * the trade comes again (its claim is kept, so a retry within the minute observes nothing new).
 */
final class PriceObservations(context: ConsumerContext) extends Consumer[ExchangeEvent, Nothing]:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[PriceObservations])

  def onMessage(event: ExchangeEvent): Effect = event match
    case e: ExchangeEvent.TradeMade =>
      val m    = messageContext.subject
      val http = Http(context.services, ExchangeRuntime.settings.reasoningService.get)
      e.prices.foreach { (outcome, price) =>
        val due = context.componentClient.forKeyValueEntity(EntityId(s"$m/${outcome.value}")).call(ObservedEntity.claim).invoke(e.at)
        if due then
          http.post(s"/markets/${Http.segment(m)}/price-observations",
            Json.obj("outcome" -> outcome.value, "price" -> price, "observedAt" -> e.at.toString)) match
            case Reply.Answered(s, _, _) if s / 100 == 2 => ()
            case Reply.Answered(s, _, text) if s < 500 => log.warn(s"the reasoning service refused an observation of $m/${outcome.value}: $s $text")
            case other => throw IllegalStateException(s"the reasoning service did not take an observation of $m/${outcome.value}: $other")
      }
      effects.done()
    case _ => effects.ignore()

object PriceObservations
    extends Consumer.Companion[PriceObservations, ExchangeEvent, Nothing](ComponentId("exchange-price-observations"),
      ChangeSource.eventsOf(ExchangeEntity)):
  def create(ctx: ConsumerContext) = new PriceObservations(ctx)
