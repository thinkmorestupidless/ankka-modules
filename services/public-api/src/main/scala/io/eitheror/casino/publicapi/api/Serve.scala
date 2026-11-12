package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Bytes, EndpointClients, Respond}
import io.eitheror.casino.publicapi.Settings
import io.eitheror.casino.publicapi.contract.{Contract, Operation, Refusals}

import java.time.Instant

/**
 * A keyed `/v1` operation answered: under the request budget, the key admitted, then the operation's handler, by its
 * operation id. An operation no story has bound yet is `501`.
 */
final class Serve(settings: Settings, contract: Contract, clients: EndpointClients, now: () => Instant,
    handlers: Map[String, (Call, Admitted) => Respond[Bytes]]):
  private val deadline = Deadline(settings.budget)

  def apply(op: Operation, call: Call): Respond[Bytes] =
    deadline.run {
      Admission.admit(clients.componentClient, call.authorization, now()) match
        case Left(refusal) => refusal.respond
        case Right(key)    => (handlers.get(op.operationId) orElse Serve.builtIn(contract).get(op.operationId)).fold(V1Endpoint.notYet)(_(call, key))
    }

object Serve:
  /** `GET /v1/contract`: the version and the page cap (original `contract/serve.ts`). */
  def builtIn(contract: Contract): Map[String, (Call, Admitted) => Respond[Bytes]] = Map(
    "getContractInfo" -> ((_, _) => Refusals.json(200, s"""{"version":"v1","maxPageSize":${contract.maxPageSize}}"""))
  )
