package io.eitheror.casino.exchange.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.predictions.exchange.*
import com.thinkmorestupidless.predictions.exchange.components.ExchangeComponents
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.exchange.{ExchangeRuntime, Json}
import io.eitheror.casino.exchange.Json.*
import io.eitheror.casino.exchange.terms.{MarketTerms, Terms}

import java.time.Instant
import scala.util.Try

import Answers.*

/**
 * `/v1/casino/brands/<slug>/exchange` (contracts/routes.md): `markets` drives a market's book. Open
 * writes the book's terms (its currency and outcomes) before the library opens it with its unit and
 * limits. Each command carries the market event's own time, which the book orders by the market's
 * clock (ankka-predictions 0.1.1): a redelivered command it has applied is refused as `state`, which
 * `markets` takes as done; one older than the market's last command is `out of order`, which `markets`
 * raises. `players` erases a player here (R13): their resting orders cancelled, the erasure
 * recorded, the certificate answered.
 */
final class ServiceEndpoint(components: ExchangeComponents, clients: EndpointClients)
    extends HttpEndpoint(s"/v1/casino/brands/${ExchangeRuntime.settings.brand}/exchange"):
  val acl: Acl = Routes.service()

  private def book(market: String) = clients.componentClient.forEventSourcedEntity(EntityId(market))

  withAcl(Routes.serviceOf("markets")) {
    postBody("/markets/{market}/{command}") { (market: String, command: String, text: String) =>
      Json.parseObject(text).fold(badBody) { b =>
        ServiceEndpoint.at(b).fold(field("at")) { at =>
          def done(events: Vector[ExchangeEvent]) = ok(Json.obj("market" -> market, "events" -> events.size))
          command match
            case "open" =>
              ServiceEndpoint.open(market, b, at).fold(field, { cmd =>
                clients.componentClient.forKeyValueEntity(EntityId(market)).call(MarketTerms.set)
                  .invoke(Terms(b.textOr("currency", ""), cmd.outcomes.map(_.value))): Unit
                answer(book(market).call(components.exchange.open).invoke(cmd))(done)
              })
            case "suspend" => answer(book(market).call(components.exchange.suspend).invoke(ExchangeCommand.Suspend(at)))(done)
            case "resume"  => answer(book(market).call(components.exchange.resume).invoke(ExchangeCommand.Resume(at)))(done)
            case "close"   => answer(book(market).call(components.exchange.close).invoke(ExchangeCommand.Close(at)))(done)
            case "finalise" =>
              val finality =
                if b.bool("void").contains(true) then Some(Finality.Void)
                else b.text("outcome").map(o => Finality.Resolved(OutcomeId(o)))
              finality.fold(field("outcome"))(f =>
                answer(book(market).call(components.exchange.finalise).invoke(ExchangeCommand.Finalise(f, at)))(done))
            case other => refusal(404, "unknown_command", s"$other is not a command of a book")
        }
      }
    }
  }

  withAcl(Routes.serviceOf("players")) {
    post("/erasure/players/{player}") { (player: String) =>
      val e = Erasure.erase(components, clients.componentClient, clients.viewClient, player, () => ExchangeRuntime.now())
      ok(Json.obj("service" -> "exchange", "playerId" -> player, "ordersCancelled" -> e.cancelled.size, "positionsKept" -> e.positionsKept,
        "erasedAt" -> e.at.map(_.toString)))
    }
  }

object ServiceEndpoint:
  def at(b: JsonNode): Option[Instant] = b.text("at").flatMap(t => Try(Instant.parse(t)).toOption)

  /** The open command a body asks for, or the field that cannot be read; the library refuses what each must be. */
  def open(market: String, b: JsonNode, at: Instant): Either[String, ExchangeCommand.Open] =
    for
      unit <- b.long("unit").toRight("unit")
      cost <- b.long("maxOrderCost").toRight("maxOrderCost")
      pos  <- b.long("maxPosition").toRight("maxPosition")
      _    <- b.text("currency").toRight("currency")
    yield ExchangeCommand.Open(MarketId(market), b.strings("outcomes").map(OutcomeId(_)), unit, cost, pos, at)
