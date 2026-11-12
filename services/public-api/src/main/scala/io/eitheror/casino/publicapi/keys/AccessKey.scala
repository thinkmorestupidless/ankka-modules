package io.eitheror.casino.publicapi.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs

import java.time.Instant

/** The contract's nine identifying field names (contracts/routes.md, `Key.identifyingFields`). */
object IdentifyingFields:
  val Names: Vector[String] = Vector("email", "firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "stateProv", "postalCode")
  val Default: Int          = 600
  val MinRate: Int          = 1
  val MaxRate: Int          = 6000

/** A key as data-model.md holds it. `digest` is `None` once deleted; no state or event carries the key. */
final case class KeyState(
    keyId: String = "",
    name: String = "",
    digest: Option[String] = None,
    enabled: Boolean = false,
    deleted: Boolean = false,
    expiresAt: Option[Instant] = None,
    identifyingFields: Set[String] = Set.empty,
    requestsPerMinute: Int = IdentifyingFields.Default,
    createdBy: String = "",
    createdAt: Option[Instant] = None
):
  def exists: Boolean = keyId.nonEmpty

enum KeyEvent:
  case KeyCreated(keyId: String, name: String, digest: String, expiresAt: Option[Instant], identifyingFields: Set[String],
      requestsPerMinute: Int, by: String, at: Instant)
  case KeyChanged(name: Option[String], expiresAt: Option[Instant], clearExpiry: Boolean, identifyingFields: Option[Set[String]],
      requestsPerMinute: Option[Int], by: String, at: Instant, was: Map[String, String] = Map.empty)
  case KeyDisabled(by: String, at: Instant)
  case KeyEnabled(by: String, at: Instant)
  case KeyDeleted(by: String, at: Instant)

final case class Create(name: String, digest: String, expiresAt: Option[Instant], identifyingFields: Set[String], requestsPerMinute: Int,
    by: String, at: Instant)
final case class Change(name: Option[String] = None, expiresAt: Option[Instant] = None, clearExpiry: Boolean = false,
    identifyingFields: Option[Set[String]] = None, requestsPerMinute: Option[Int] = None, by: String, at: Instant)
final case class By(by: String, at: Instant)
final case class Admit(digest: String, now: Instant)

/** `admit`'s answer: a refusal's code, or the key's fields and rate. */
final case class Admission(refused: Option[String] = None, identifyingFields: Set[String] = Set.empty, requestsPerMinute: Int = 0):
  def admitted: Boolean = refused.isEmpty

final class AccessKey(context: EventSourcedEntityContext) extends EventSourcedEntity[KeyState, KeyEvent]:
  import KeyEvent.*

  def emptyState: KeyState                       = KeyState()
  def applyEvent(event: KeyEvent): KeyState      = AccessKey.applied(currentState, event)
  private def deleted                            = effects.error(AccessKey.Deleted, ErrorCode.Conflict)
  private def absent                             = effects.error(s"no key ${context.entityId}", ErrorCode.NotFound)

  def create(c: Create): Effect[KeyState] =
    if !currentState.exists then
      effects.persist(KeyCreated(context.entityId, c.name, c.digest, c.expiresAt, c.identifyingFields, c.requestsPerMinute, c.by, c.at))
        .thenReplyState
    else if currentState.digest.contains(c.digest) then effects.reply(currentState)
    else effects.error(s"key ${context.entityId} exists", ErrorCode.Conflict)

  def change(c: Change): Effect[KeyState] =
    if !currentState.exists then absent
    else if currentState.deleted then deleted
    else effects.persist(KeyChanged(c.name, c.expiresAt, c.clearExpiry, c.identifyingFields, c.requestsPerMinute, c.by, c.at,
      AccessKey.was(currentState, c))).thenReplyState

  /** Idempotent, on a deleted key too. */
  def disable(b: By): Effect[KeyState] =
    if !currentState.exists then absent
    else if !currentState.enabled then effects.reply(currentState)
    else effects.persist(KeyDisabled(b.by, b.at)).thenReplyState

  def enable(b: By): Effect[KeyState] =
    if !currentState.exists then absent
    else if currentState.deleted then deleted
    else if currentState.enabled then effects.reply(currentState)
    else effects.persist(KeyEnabled(b.by, b.at)).thenReplyState

  /** Idempotent: the digest is dropped, so the key can never be admitted again. */
  def delete(b: By): Effect[KeyState] =
    if !currentState.exists then absent
    else if currentState.deleted then effects.reply(currentState)
    else effects.persist(KeyDeleted(b.by, b.at)).thenReplyState

  def get: ReadOnlyEffect[KeyState] = if currentState.exists then effects.reply(currentState) else absent

  def admit(a: Admit): ReadOnlyEffect[Admission] = effects.reply(AccessKey.admission(currentState, a))

object AccessKey extends EventSourcedEntity.Companion[AccessKey, KeyState, KeyEvent](
      componentId = ComponentId("access-key"),
      stateSerializer = PublicApiCodecs.serializer[KeyState]("access-key"),
      eventSerializer = PublicApiCodecs.serializer[KeyEvent]("access-key-event")
    ):
  given Serializer[KeyState]  = PublicApiCodecs.serializer[KeyState]("access-key-reply")
  given Serializer[Create]    = PublicApiCodecs.serializer[Create]("access-key-create")
  given Serializer[Change]    = PublicApiCodecs.serializer[Change]("access-key-change")
  given Serializer[By]        = PublicApiCodecs.serializer[By]("access-key-by")
  given Serializer[Admit]     = PublicApiCodecs.serializer[Admit]("access-key-admit")
  given Serializer[Admission] = PublicApiCodecs.serializer[Admission]("access-key-admission")

  /** The refusal a command on a deleted key is answered with (contracts/routes.md, `409 key_deleted`). */
  val Deleted = "key_deleted"

  def create(context: EventSourcedEntityContext) = new AccessKey(context)

  val create  = command("create")(_.create)
  val change  = command("change")(_.change)
  val disable = command("disable")(_.disable)
  val enable  = command("enable")(_.enable)
  val delete  = command("delete")(_.delete)
  val get     = query("get")(_.get)
  val admit   = query("admit")(_.admit)

  /** What a change replaces, for the audit's `before` (FR-021): each changed field's value before it, as text. */
  def was(s: KeyState, c: Change): Map[String, String] =
    Map.empty[String, String] ++
      c.name.map(_ => "name" -> s.name) ++
      Option.when(c.expiresAt.isDefined || c.clearExpiry)("expiresAt" -> s.expiresAt.fold("")(_.toString)) ++
      c.identifyingFields.map(_ => "identifyingFields" -> IdentifyingFields.Names.filter(s.identifyingFields).mkString(",")) ++
      c.requestsPerMinute.map(_ => "requestsPerMinute" -> s.requestsPerMinute.toString)

  def applied(s: KeyState, e: KeyEvent): KeyState = e match
    case KeyEvent.KeyCreated(id, name, digest, expires, fields, rate, by, at) =>
      KeyState(id, name, Some(digest), enabled = true, deleted = false, expires, fields, rate, by, Some(at))
    case KeyEvent.KeyChanged(name, expires, clear, fields, rate, _, _, _) =>
      s.copy(
        name = name.getOrElse(s.name),
        expiresAt = if clear then None else expires.orElse(s.expiresAt),
        identifyingFields = fields.getOrElse(s.identifyingFields),
        requestsPerMinute = rate.getOrElse(s.requestsPerMinute))
    case KeyEvent.KeyDisabled(_, _) => s.copy(enabled = false)
    case KeyEvent.KeyEnabled(_, _)  => s.copy(enabled = true)
    case KeyEvent.KeyDeleted(_, _)  => s.copy(deleted = true, enabled = false, digest = None)

  /**
   * data-model.md's order: an unknown, deleted or wrong key is `key_invalid` whatever else is true of it, so a wrong
   * secret never learns whether the key is disabled or expired; then expired, then disabled.
   */
  def admission(s: KeyState, a: Admit): Admission =
    if !s.exists || s.deleted || !s.digest.exists(RawKeys.same(_, a.digest)) then Admission(Some("key_invalid"))
    else if s.expiresAt.exists(x => !a.now.isBefore(x)) then Admission(Some("key_expired"))
    else if !s.enabled then Admission(Some("key_disabled"))
    else Admission(None, s.identifyingFields, s.requestsPerMinute)
