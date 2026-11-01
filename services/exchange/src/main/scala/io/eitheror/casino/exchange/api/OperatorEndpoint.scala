package io.eitheror.casino.exchange.api

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.exchange.{ExchangeRuntime, Json}
import io.eitheror.casino.exchange.Json.*
import io.eitheror.casino.exchange.retry.{Abandon, RetryState, SettlementRetry}

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>/exchange` (contracts/routes.md): the brand's operators with the markets
 * permission abandon a settlement retry (R5), with a reason, which the audit record names and never writes.
 */
final class OperatorEndpoint(clients: EndpointClients, authenticate: () => Acl)
    extends HttpEndpoint(s"/v1/casino/backoffice/${ExchangeRuntime.settings.brand}/exchange"):
  val acl: Acl = Routes.operatorHolding(ExchangeRuntime.settings.brand, "markets", "platform_admin")(authenticate())

  private def retry(key: String) = clients.componentClient.forWorkflow(EntityId(key))

  get("/retries/{key}") { (key: String) => answered(retry(key).call(SettlementRetry.get).invoke()) }

  postBody("/retries/{key}") { (key: String, text: String) =>
    Json.parseObject(text).fold(badBody) { b =>
      (b.bool("abandon"), b.text("reason")) match
        case (Some(true), Some(reason)) => answered(retry(key).call(SettlementRetry.abandon).invoke(Abandon(principal.subject, reason)))
        case (Some(true), None)         => field("reason")
        case _                          => field("abandon")
    }
  }

  private def answered(call: => RetryState): Reply =
    try
      val s = call
      ok(Json.obj("key" -> s.call.map(_.key), "status" -> s.status, "attempts" -> s.attempts, "lastRefusal" -> s.lastRefusal))
    catch
      case e: CommandError if e.code == ErrorCode.NotFound => refusal(404, "retry_not_found", "No such retry")
      case e: CommandError if e.code == ErrorCode.Conflict => refusal(409, "state", e.message)
