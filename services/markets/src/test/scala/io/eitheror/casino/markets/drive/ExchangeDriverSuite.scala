package io.eitheror.casino.markets.drive

import com.thinkmorestupidless.ankka.sdk.{ServiceClient, ServiceClients, ServiceResponse, ServiceUnanswered}
import com.thinkmorestupidless.predictions.markets.*
import io.eitheror.casino.markets.{MarketsRuntime, Settings}
import io.eitheror.casino.markets.terms.Terms

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}

/** Each market event to its call on the book (R2), and every answer of the exchange's to what the driver does with it. */
class ExchangeDriverSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")

  override def beforeAll(): Unit =
    MarketsRuntime.wire(Settings("goldvibe", Vector("politics"), Vector("EUR"), 100000, 5000, 0))

  private val definition = Definition("Q?", "D", "politics", Vector(OutcomeId("yes"), OutcomeId("no")), "EUR", None,
    at.plusSeconds(3600), LocalDate.parse("2026-11-02"), "criteria", "authority")
  private val open = Market(MarketId("m1"), Some(definition), MarketState.Open)

  private def command(e: MarketEvent, market: Market = open, terms: Terms = Terms()) = ExchangeDriver.command(e, () => market, () => terms)

  test("at Opened the book opens with the outcomes, the currency, a unit of 100 and the brand's limits") {
    val (name, body) = command(MarketEvent.Opened(at)).get
    assertEquals(name, "open")
    assertEquals(body.get("outcomes").toString, """["yes","no"]""")
    assertEquals(body.get("currency").asText, "EUR")
    assertEquals(body.get("unit").asLong, 100L)
    assertEquals(body.get("maxOrderCost").asLong, 100000L)
    assertEquals(body.get("maxPosition").asLong, 5000L)
    assertEquals(body.get("takerFeeBps").asInt, 0)
    assertEquals(body.get("at").asText, at.toString)
  }

  test("a market's own limits stand in for the brand's") {
    val (_, body) = command(MarketEvent.Opened(at), terms = Terms(Some(2000), None)).get
    assertEquals((body.get("maxOrderCost").asLong, body.get("maxPosition").asLong), (2000L, 5000L))
  }

  test("suspend, resume and close follow; the market's other changes are not the book's") {
    assertEquals(command(MarketEvent.Suspended(OperatorId("op"), "r", at)).map(_._1), Some("suspend"))
    assertEquals(command(MarketEvent.Resumed(OperatorId("op"), at)).map(_._1), Some("resume"))
    assertEquals(command(MarketEvent.Closed(None, None, at)).map(_._1), Some("close"))
    assertEquals(command(MarketEvent.Approved(OperatorId("op"), at)), None)
    assertEquals(command(MarketEvent.Changed(OperatorId("op"), definition, at)), None)
  }

  test("finality names the outcome in force, or void") {
    val resolved = open.copy(state = MarketState.Final, resolutions = Vector(
      Resolution(OutcomeId("no"), Vector.empty, "a", OperatorId("op"), at, at),
      Resolution(OutcomeId("yes"), Vector.empty, "a", OperatorId("op"), at, at)))
    assertEquals(command(MarketEvent.Final(at), resolved).get._2.get("outcome").asText, "yes")
    val voided = open.copy(state = MarketState.Final, void = Some(VoidRecord("r", Vector.empty, OperatorId("op"), at, at)))
    assert(command(MarketEvent.Final(at), voided).get._2.get("void").asBoolean)
  }

  private def calls(status: Option[(Int, String)]) =
    val services = new ServiceClients:
      def apply(name: String): ServiceClient = new ServiceClient:
        def target = s"local/$name"
        def request(method: String, path: String, body: Option[Array[Byte]], contentType: Option[String], headers: Seq[(String, String)]) =
          assertEquals(path, "/v1/casino/brands/goldvibe/exchange/markets/m%201/open")
          status.fold(throw ServiceUnanswered(name, "down"))((s, t) => ServiceResponse(s, "application/json", t.getBytes(UTF_8), Vector.empty))
      def apply(project: String, name: String): ServiceClient = apply(name)
    ExchangeCalls(services, "goldvibe")

  private def send(status: Option[(Int, String)]) = calls(status).send("m 1", "open", io.eitheror.casino.markets.Json.obj())

  test("a 2xx is done, and so is a redelivery the book refuses as its state or out of order") {
    assertEquals(send(Some(200 -> "{}")), Driven.Done)
    assertEquals(send(Some(409 -> """{"error":"state"}""")), Driven.Done)
    assertEquals(send(Some(409 -> """{"error":"out of order"}""")), Driven.Done)
  }

  test("any other refusal is raised with its code; a 5xx or no answer throws, so the event comes again") {
    assertEquals(send(Some(400 -> """{"error":"limit"}""")), Driven.Refused(400, "limit"))
    assertEquals(send(Some(409 -> """{"error":"outcome"}""")), Driven.Refused(409, "outcome"))
    intercept[IllegalStateException](send(Some(503 -> "{}")))
    intercept[ServiceUnanswered](send(None))
  }
