package io.eitheror.casino.ingest.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.IngestCodecs

/** A key as the request path reads it: its id, its digest, its sub-brand and whether it is active. */
final case class KeyRow(keyId: String, digest: String, subBrand: String, active: Boolean = false, deactivated: Boolean = false)

object KeyIndexCodecs:
  given Serializer[KeyRow]         = IngestCodecs.serializer[KeyRow]("key-row")
  given Serializer[Option[KeyRow]] = IngestCodecs.serializer[Option[KeyRow]]("key-row-read")

import KeyIndexCodecs.given

/**
 * The request path's read of the keys (SC-003; research R3), under the key's digest: one point read per
 * request. A view queried by digest was a scan of every key (no index is declarable in ankka 0.11), and at
 * 10,000 keys its p99 was 9–13 ms against SC-003's 5 ms, growing with the keys. Written by `KeyFacts` and
 * `KeyRefresh` from what `Key` decided; once deactivated it is never active again, so a refresh's late
 * write cannot undo a deactivation that landed first.
 */
final class KeyIndex(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Option[KeyRow]]:

  def emptyState: Option[KeyRow] = None

  def set(row: KeyRow): Effect[Option[KeyRow]] =
    currentState match
      case Some(s) if s.deactivated || s == row => effects.reply(currentState)
      case _                                    => effects.updateState(Some(row)).thenReplyState

  def read: ReadOnlyEffect[Option[KeyRow]] = effects.reply(currentState)

object KeyIndex extends KeyValueEntity.Companion[KeyIndex, Option[KeyRow]](
      componentId = ComponentId("key-index"),
      stateSerializer = KeyIndexCodecs.given_Serializer_Option
    ):
  def create(context: KeyValueEntityContext) = new KeyIndex(context)

  val set  = command("set")(_.set)
  val read = query("read")(_.read)

  /** The key under a digest, if any key was ever issued to it. */
  def find(components: ComponentClient, digest: String): Option[KeyRow] =
    components.forKeyValueEntity(EntityId(digest)).call(read).invoke()

  /** What `Key` decided for `keyId`, written under its digest; a key with no digest yet has nothing to write. */
  def record(components: ComponentClient, keyId: String, state: KeyState): Unit =
    if state.digest.nonEmpty then
      components.forKeyValueEntity(EntityId(state.digest)).call(set)
        .invoke(KeyRow(keyId, state.digest, state.subBrand, state.active, state.deactivated)): Unit
