package io.eitheror.casino.markets.drive

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.{ConsumerContext, ServiceClients}
import com.thinkmorestupidless.predictions.markets.{Market, MarketEvent}
import com.thinkmorestupidless.predictions.markets.components.{MarketEntityCompanion, MarketsComponents}
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}
import io.eitheror.casino.markets.{Json, MarketsRuntime}
import io.eitheror.casino.markets.terms.{MarketTerms, Terms}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** What `exchange` answered a command: done (a 2xx, or a repeat it refuses as `state`), or refused with its code. */
enum Driven:
  case Done
  case Refused(status: Int, error: String)

/**
 * `exchange`'s service route for a market's book (contracts/routes.md), as `markets` calls it. An
 * exchange that cannot be reached, or answers 5xx, throws, so the event is delivered again. A
 * market's events reach the driver in order, so the book refuses one as `state` (applied) or `out of
 * order` (older than a later one it applied) only on redelivery, which ankka makes of every change
 * since a consumer's last saved offset; both are done.
 */
final class ExchangeCalls(services: ServiceClients, brand: String):
  private def segment(s: String) = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  def send(market: String, command: String, body: JsonNode): Driven =
    val path = s"/v1/casino/brands/$brand/exchange/markets/${segment(market)}/$command"
    val r = services(ExchangeCalls.Service).request("POST", path, Some(Json.render(body).getBytes(UTF_8)), Some("application/json"), Nil)
    val error = Json.parse(String(r.body, UTF_8)).flatMap(n => Option(n.get("error"))).map(_.asText).getOrElse("")
    if r.status / 100 == 2 || (r.status == 409 && ExchangeCalls.Applied(error)) then Driven.Done
    else if r.status >= 500 then throw IllegalStateException(s"exchange answered $command on $market with ${r.status}")
    else Driven.Refused(r.status, error)

object ExchangeCalls:
  val Service = "exchange"
  /** The book's refusals of a command it has applied, or that a later one it applied supersedes. */
  val Applied: Set[String] = Set("state", "out of order")

/**
 * The library's market drives the exchange's book (spec 023 R2): at `Opened` the book opens with the
 * market's outcomes and currency, a share's unit of 100 minor units (FR-012), the draft's limits or the
 * brand's (FR-014) and the brand's taker fee rate (FR-015); suspend, resume, close and finality follow.
 * A refusal other than a repeat is raised on `platform.audit` and the event is not skipped silently.
 */
final class ExchangeDriver(context: ConsumerContext, components: MarketsComponents) extends AuditConsumer[MarketEvent]:
  private def settings = MarketsRuntime.settings
  private def calls    = MarketsRuntime.exchange(context)

  protected def audit(event: MarketEvent): Option[AuditRecord] =
    val id = messageContext.subject
    ExchangeDriver.command(event, market(id), terms(id)).flatMap { (command, body) =>
      calls.send(id, command, body) match
        case Driven.Done => None
        case Driven.Refused(status, error) =>
          val recordId = s"exchange-driver/$id/${messageContext.sequenceNumber}"
          Some(AuditRecord(recordId = recordId, brand = settings.brand, service = "markets", action = s"exchange.$command",
            actorKind = "system", actorId = "markets", approverId = None, subjectKind = "market", subjectId = id, outcome = "refused",
            reasonCode = Some(error), changes = Vector(AuditChange("status", None, Some(status.toString))), correlationId = recordId,
            causationId = recordId, recordClass = "system", occurredAt = event.at))
    }

  private def market(id: String): () => Market =
    () => context.componentClient.forEventSourcedEntity(EntityId(id)).call(components.market.get).invoke()
  private def terms(id: String): () => Terms =
    () => context.componentClient.forKeyValueEntity(EntityId(id)).call(MarketTerms.get).invoke()

object ExchangeDriver:
  /** A share pays one major unit, 100 minor units, so every book's unit is 100 (FR-012). */
  val Unit: Long = 100

  /** The exchange's command and body for a market event, or none for an event the book does not follow. */
  def command(event: MarketEvent, market: () => Market, terms: () => Terms): Option[(String, JsonNode)] =
    val s = MarketsRuntime.settings
    event match
      case _: MarketEvent.Opened =>
        val m          = market()
        val d          = m.definition.getOrElse(throw IllegalStateException(s"market ${m.market.value} opened with no definition"))
        val (cost, pos) = terms().orDefaults(s.maxOrderCost, s.maxPosition)
        Some("open" -> Json.obj("outcomes" -> d.outcomes.map(_.value), "currency" -> d.currency, "unit" -> Unit,
          "maxOrderCost" -> cost, "maxPosition" -> pos, "takerFeeBps" -> s.takerFeeBps, "at" -> event.at.toString))
      case _: MarketEvent.Suspended => Some("suspend" -> Json.obj("at" -> event.at.toString))
      case _: MarketEvent.Resumed   => Some("resume" -> Json.obj("at" -> event.at.toString))
      case _: MarketEvent.Closed    => Some("close" -> Json.obj("at" -> event.at.toString))
      case _: MarketEvent.Final =>
        val m = market()
        val finality =
          if m.void.isDefined then Json.obj("void" -> true, "at" -> event.at.toString)
          else Json.obj("outcome" -> m.resolution.map(_.outcome.value).getOrElse(throw IllegalStateException(s"market ${m.market.value} final with no resolution")),
            "at" -> event.at.toString)
        Some("finalise" -> finality)
      case _ => None

final class ExchangeDriverCompanion(components: MarketsComponents)
    extends AuditCompanion[ExchangeDriver, MarketEvent]("exchange-driver", MarketEntityCompanion.events):
  def create(ctx: ConsumerContext) = new ExchangeDriver(ctx, components)
