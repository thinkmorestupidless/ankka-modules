package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.publicapi.contract.{Refusal, Refusals}
import io.eitheror.casino.publicapi.keys
import io.eitheror.casino.publicapi.keys.{Disclosed, KeyLog, KeyWindow, Take}

import java.time.Instant
import java.util.UUID

/**
 * The pipeline's steps 4 and 6 (contracts/routes.md): the key's rate, counted in `KeyWindow(keyId)` so every instance
 * of the service shares one count (FR-031), and the key's log, one `KeyLog(keyId)` event per request the audit reads
 * (FR-037). A log that cannot be written leaves the answer as it is: the request was served.
 */
final class Usage(components: ComponentClient):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[Usage])

  def rate(key: Admitted, now: Instant): Option[Refusal] =
    val t = components.forKeyValueEntity(EntityId(key.keyId)).call(KeyWindow.take).invoke(Take(now, key.requestsPerMinute))
    Option.unless(t.allowed)(Refusals.quotaExceeded(t.retryAfter.toLong))

  def served(now: () => Instant)(call: Call, key: Admitted, answer: Respond[Bytes], ms: Long, disclosed: Vector[(String, Vector[String])],
      unrecorded: Int): Unit =
    val entry = keys.Served(UUID.randomUUID().toString, Usage.operation(call), answer.status, KeyLog.outcome(answer.status), ms,
      disclosed.map((p, f) => Disclosed(p, f)), now(), unrecorded)
    try components.forEventSourcedEntity(EntityId(key.keyId)).call(KeyLog.served).invoke(entry): Unit
    catch case scala.util.control.NonFatal(e) => log.warn("public_api_key_log_failed key={} {}", key.keyId, e.getMessage)

object Usage:
  /** `GET <template> <name>=<id>,…`: the operation and its ids, never the query or a value of a player's. */
  def operation(call: Call): String =
    val template = call.operation.fold("unknown")(o => s"${o.method} ${o.template}")
    if call.ids.isEmpty then template else s"$template ${call.ids.map((n, v) => s"$n=$v").mkString(",")}"
