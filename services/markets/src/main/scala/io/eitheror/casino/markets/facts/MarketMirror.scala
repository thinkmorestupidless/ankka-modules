package io.eitheror.casino.markets.facts

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.markets.{Market, MarketEvent}
import com.thinkmorestupidless.predictions.markets.components.{MarketEntityCompanion, MarketsComponents}
import io.eitheror.casino.markets.{Json, MarketsRuntime}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * The reasoning service's belief and market layers (ankka-reasoning contracts/http-api.md), as this
 * service writes to them: a write it already holds (409) is done, as is any 2xx; a service that cannot
 * be reached or answers 5xx throws, so the change is delivered again; any other answer is refused.
 */
final class ReasoningCalls(services: ServiceClients, target: String):
  def post(path: String, body: JsonNode): Either[String, Unit] =
    val r = services(target).request("POST", path, Some(Json.render(body).getBytes(UTF_8)), Some("application/json"), Nil)
    if r.status / 100 == 2 || r.status == 409 then Right(())
    else if r.status >= 500 then throw IllegalStateException(s"$target answered $path with ${r.status}")
    else Left(s"${r.status}: ${String(r.body, UTF_8)}")

/**
 * Each approved market mirrored to the reasoning service the brand's values name (FR-007): a question
 * with one hypothesis per outcome, then a market of that question with the brand as its venue. A
 * refusal is logged, never retried: the service has said no.
 */
final class MarketMirror(context: ConsumerContext, components: MarketsComponents) extends Consumer[MarketEvent, Nothing]:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[MarketMirror])

  def onMessage(event: MarketEvent): Effect = event match
    case _: MarketEvent.Approved =>
      val id = messageContext.subject
      val m  = context.componentClient.forEventSourcedEntity(EntityId(id)).call(components.market.get).invoke()
      val calls = MarketsRuntime.reasoning(context)
      MarketMirror.writes(m, MarketsRuntime.settings.brand).foreach { (path, body) =>
        calls.post(path, body).left.foreach(e => log.warn(s"the reasoning service refused $path for market $id: $e"))
      }
      effects.done()
    case _ => effects.ignore()

object MarketMirror:
  /** A hypothesis's id: the market's and the outcome's. */
  def hypothesis(market: String, outcome: String) = s"$market.$outcome"

  /** The question and then the market, as the reasoning service is written to. */
  def writes(m: Market, brand: String): Vector[(String, JsonNode)] = m.definition.toVector.flatMap { d =>
    val id       = m.market.value
    val outcomes = d.outcomes.map(_.value)
    Vector(
      "/questions" -> Json.obj("id" -> id, "statement" -> d.question,
        "hypotheses" -> Json.arr(outcomes.map(o => Json.obj("id" -> hypothesis(id, o), "statement" -> s"${d.question} $o"))*)),
      "/markets" -> Json.obj("id" -> id, "question" -> id, "venue" -> brand,
        "outcomes" -> Json.arr(outcomes.map(o => Json.obj("outcome" -> o, "hypothesis" -> hypothesis(id, o)))*),
        "resolutionCriteria" -> d.criteria, "closesAt" -> d.close.toString)
    )
  }

final class MarketMirrorCompanion(components: MarketsComponents)
    extends Consumer.Companion[MarketMirror, MarketEvent, Nothing](ComponentId("market-mirror"), MarketEntityCompanion.events):
  def create(ctx: ConsumerContext) = new MarketMirror(ctx, components)
