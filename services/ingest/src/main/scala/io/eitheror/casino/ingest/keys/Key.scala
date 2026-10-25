package io.eitheror.casino.ingest.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.IngestCodecs

import java.time.Instant

/**
 * A cashier key as `merchant` has told this service of it (FR-002, FR-004), under its key id: the digest of
 * the raw key, never the key, and the sub-brand that holds it. A deactivated key stays, inactive, with no
 * expiry, so a later `issue` from the start refresh cannot revive it (the order of a refresh and a fact is
 * not fixed). A deactivation the index never saw an issue for is kept too, with no digest.
 */
final case class KeyState(
    digest: String = "",
    subBrand: String = "",
    active: Boolean = false,
    deactivated: Boolean = false,
    since: Option[Instant] = None
)

final case class Issue(digest: String, subBrand: String, at: Instant)
final case class Deactivate(at: Instant)

object KeyCodecs:
  given Serializer[KeyState]   = IngestCodecs.serializer[KeyState]("key-state")
  given Serializer[Issue]      = IngestCodecs.serializer[Issue]("key-issue")
  given Serializer[Deactivate] = IngestCodecs.serializer[Deactivate]("key-deactivate")

import KeyCodecs.given

final class Key(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[KeyState]:

  def emptyState: KeyState = KeyState()

  /** Active under the digest; a repeat changes nothing; a deactivated key is never revived. */
  def issue(cmd: Issue): Effect[KeyState] =
    val s = currentState
    if s.deactivated || (s.active && s.digest == cmd.digest && s.subBrand == cmd.subBrand) then effects.reply(s)
    else effects.updateState(KeyState(cmd.digest, cmd.subBrand, active = true, since = Some(cmd.at))).thenReplyState

  def deactivate(cmd: Deactivate): Effect[KeyState] =
    if currentState.deactivated then effects.reply(currentState)
    else effects.updateState(currentState.copy(active = false, deactivated = true, since = Some(cmd.at))).thenReplyState

  def read: ReadOnlyEffect[KeyState] = effects.reply(currentState)

object Key extends KeyValueEntity.Companion[Key, KeyState](
      componentId = ComponentId("key"),
      stateSerializer = KeyCodecs.given_Serializer_KeyState
    ):
  def create(context: KeyValueEntityContext) = new Key(context)

  val issue      = command("issue")(_.issue)
  val deactivate = command("deactivate")(_.deactivate)
  val read       = query("read")(_.read)
