package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Bytes, EndpointClients, Respond}
import io.eitheror.casino.publicapi.Settings
import io.eitheror.casino.publicapi.contract.{Contract, Operation, Refusal, Refusals}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Try

/** A keyed request that passed the pipeline: its call, its key, and its path ids checked (UUIDs lower-cased). */
final case class Served(call: Call, key: Admitted, ids: Map[String, String]):
  def id(name: String): String = ids(name)

  /** The players this answer served and the identifying fields served of each, for the audit (FR-021, step 6). */
  val disclosed: scala.collection.mutable.ArrayBuffer[(String, Vector[String])] = scala.collection.mutable.ArrayBuffer.empty

/**
 * contracts/routes.md's pipeline, steps 2 to 5, for every keyed `/v1` operation, all under the request budget
 * (`Deadline`): the key presented and admitted, its rate (`rate`, step 4), the path ids, then the handler by operation
 * id. After every answer to a request that named a key of this brand, `served` is told of it (step 6, the key's log); a key
 * id no key was created under is recorded nowhere, so a made-up key costs the brand no write, and a key's refusals are
 * recorded up to `RefusalCap`'s bound a minute (FR-036). The pipeline is built once per endpoint: its budget's threads
 * and the cap's counts are its own. A refusal never
 * carries what a handler held, and only a `2xx` answer reports the players it disclosed.
 */
final class Pipeline(
    settings: Settings,
    contract: Contract,
    clients: EndpointClients,
    now: () => Instant,
    handlers: Map[String, Served => Respond[Bytes]],
    rate: (Admitted, Instant) => Option[Refusal] = (_, _) => None,
    served: (Call, Admitted, Respond[Bytes], Long, Vector[(String, Vector[String])], Int) => Unit = (_, _, _, _, _, _) => ()
):
  private val deadline = Deadline(settings.budget)
  private val cap      = RefusalCap(RefusalCap.PerMinute, now)
  private val all      = Pipeline.builtIn(contract) ++ handlers

  def apply(op: Operation, call: Call): Respond[Bytes] =
    val started = System.nanoTime()
    // FR-036: a request that named a key of this brand is recorded, refused or not; one refused before a key is named,
    // or naming a key id no key was created under, is not. One whose admission outlived the budget is not known to name one.
    val admitted = java.util.concurrent.atomic.AtomicReference[Option[Admitted]](None)
    val request  = java.util.concurrent.atomic.AtomicReference[Option[Served]](None)
    val answer = deadline.run {
      Admission.admit(clients.componentClient, call.authorization, now()) match
        case Left(rejected) =>
          rejected.key.foreach(id => admitted.set(Some(Admitted(id, Set.empty, 0))))
          rejected.refusal.respond
        case Right(key) =>
          admitted.set(Some(key))
          rate(key, now()).map(_.respond).getOrElse {
            Pipeline.checkIds(call).fold(_.respond, ids => all.get(op.operationId).fold(V1Endpoint.notYet) { h =>
              val r = Served(call, key, ids); request.set(Some(r)); h(r)
            })
          }
    }
    val shown = Pipeline.disclosed(answer, request.get)
    // A key's refusals (every 4xx: at admission, its quota's 429, a bad id) are recorded up to the cap a minute, the rest
    // counted onto its next recorded one (FR-036). A 4xx discloses no one, so the cap loses no disclosure.
    admitted.get.foreach { k =>
      val unrecorded = if answer.status / 100 == 4 then cap.refused(k.keyId) else Some(0)
      unrecorded.foreach(n => Try(served(call, k, answer, (System.nanoTime() - started) / 1000000, shown, n)))
    }
    answer

object Pipeline:
  /**
   * The players an answer disclosed, for the audit. A `2xx` answer is the handler's own, finished before the budget's
   * `run` returned; any other may be the budget's `503` with the handler still running and still adding to its list,
   * so it discloses nothing (FR-036: only identifying fields served are recorded).
   */
  def disclosed(answer: Respond[Bytes], request: Option[Served]): Vector[(String, Vector[String])] =
    if answer.status / 100 == 2 then request.fold(Vector.empty)(_.disclosed.toVector) else Vector.empty

  /** The contract's identifier formats: `Uuid` and `GameCode` (original `rest/router.ts` VALIDATORS). */
  private val Uuid     = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r
  private val GameCode = "^[A-Za-z0-9._-]{1,64}$".r

  /** Each path id decoded and checked by its kind; a UUID is lower-cased (original `router.ts:148-151`). */
  def checkIds(call: Call): Either[Refusal, Map[String, String]] =
    call.ids.foldLeft[Either[Refusal, Map[String, String]]](Right(Map.empty)) { case (acc, (name, raw)) =>
      acc.flatMap { m =>
        val decoded = Try(URLDecoder.decode(raw.replace("+", "%2B"), UTF_8)).toOption
        val ok =
          if name == "gameId" then decoded.filter(v => GameCode.matches(v))
          else decoded.filter(v => Uuid.matches(v)).map(_.toLowerCase)
        ok.map(v => m + (name -> v)).toRight(Refusals.notAnIdentifier(name))
      }
    }

  /** `GET /v1/contract`: the version and the page cap (original `contract/serve.ts`). */
  def builtIn(contract: Contract): Map[String, Served => Respond[Bytes]] = Map(
    "getContractInfo" -> (_ => Refusals.json(200, s"""{"version":"v1","maxPageSize":${contract.maxPageSize}}"""))
  )
