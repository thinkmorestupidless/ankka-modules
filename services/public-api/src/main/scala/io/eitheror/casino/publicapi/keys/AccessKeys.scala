package io.eitheror.casino.publicapi.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs

/** One row per key, contracts/routes.md's `Key` without `lastUsedAt` (the view `KeyUse` gives that). */
final case class KeyRow(
    keyId: String = "",
    name: String = "",
    enabled: Boolean = false,
    deleted: Boolean = false,
    expiresAt: Option[java.time.Instant] = None,
    identifyingFields: Vector[String] = Vector.empty,
    requestsPerMinute: Int = IdentifyingFields.Default,
    createdBy: String = "",
    createdAt: Option[java.time.Instant] = None
)

object KeyRow:
  given Serializer[KeyRow] = PublicApiCodecs.row[KeyRow]("key-row")

  def of(s: KeyState): KeyRow =
    KeyRow(s.keyId, s.name, s.enabled, s.deleted, s.expiresAt, IdentifyingFields.Names.filter(s.identifyingFields), s.requestsPerMinute,
      s.createdBy, s.createdAt)

final class AccessKeysView extends View[KeyEvent, KeyRow]:
  def onChange(event: KeyEvent): Effect =
    val before = rowState.fold(KeyState(keyId = updateContext.subject))(r =>
      KeyState(r.keyId, r.name, None, r.enabled, r.deleted, r.expiresAt, r.identifyingFields.toSet, r.requestsPerMinute, r.createdBy, r.createdAt))
    effects.updateRow(KeyRow.of(AccessKey.applied(before, event)))

object AccessKeys extends View.Companion[AccessKeysView, KeyEvent, KeyRow](
      componentId = ComponentId("access-keys"),
      source = ChangeSource.eventsOf(AccessKey),
      rowSerializer = KeyRow.given_Serializer_KeyRow
    ):
  def create(ctx: ViewComponentContext) = new AccessKeysView

  /** Every key, oldest first, deleted ones only when asked (`:deleted` is `true` or `false`). */
  val listed = query("listed")(
    s"SELECT payload FROM $table WHERE (:deleted = 'true' OR payload::jsonb->>'deleted' = 'false') ORDER BY payload::jsonb->>'createdAt', row_key")
