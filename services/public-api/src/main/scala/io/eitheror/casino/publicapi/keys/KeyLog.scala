package io.eitheror.casino.publicapi.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs

import java.time.Instant

/** A player served in an answer and the identifying fields served of them (names only). */
final case class Disclosed(playerId: String, fields: Vector[String])

/**
 * One request that named a key (data-model.md `KeyLog`, research R6): `operation` is `GET <template> <name>=<id>,…`,
 * `outcome` is `done`, `refused` or `failed`. No value, no key, no digest.
 */
final case class Served(requestId: String, operation: String, status: Int, outcome: String, durationMs: Long,
    players: Vector[Disclosed], at: Instant)

/** The state is the count since the last snapshot: the journal is the log, read by the audit's consumer. */
final case class LogState(count: Long = 0L)

final class KeyLog(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[LogState, Served]:
  def emptyState: LogState                  = LogState()
  def applyEvent(event: Served): LogState   = currentState.copy(count = currentState.count + 1)
  def served(s: Served): Effect[LogState]   = effects.persist(s).thenReplyState

object KeyLog extends EventSourcedEntity.Companion[KeyLog, LogState, Served](
      componentId = ComponentId("key-log"),
      stateSerializer = PublicApiCodecs.serializer[LogState]("key-log"),
      eventSerializer = PublicApiCodecs.serializer[Served]("key-log-served")
    ):
  given Serializer[Served]   = PublicApiCodecs.serializer[Served]("key-log-serve")
  given Serializer[LogState] = PublicApiCodecs.serializer[LogState]("key-log-reply")

  override def snapshotEvery: Option[Int] = Some(500)

  def create(context: EventSourcedEntityContext) = new KeyLog(context)

  val served = command("served")(_.served)

  def outcome(status: Int): String = if status < 400 then "done" else if status < 500 then "refused" else "failed"
