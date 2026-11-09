package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs
import io.eitheror.casino.audit.ids.RegistryKeyBytes

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * A message the store would not take (FR-012, research R11), id `<source>/<SHA-256 of its bytes>`: the
 * bytes as they arrived, sealed under `quarantine-key` since they nearly always name a person (SC-002;
 * research, *Found during implementation*), from where, when and why. Held once; the same message again
 * changes nothing.
 */
final case class QuarantinedState(source: String = "", why: String = "", sealedBytes: String = "", arrivedAt: Option[Instant] = None):
  def held: Boolean = why.nonEmpty

final case class Hold(source: String, why: String, sealedBytes: String, arrivedAt: Instant)

object QuarantinedCodecs:
  given Serializer[QuarantinedState] = AuditCodecs.serializer[QuarantinedState]("quarantined")
  given Serializer[Hold]             = AuditCodecs.serializer[Hold]("hold")

import QuarantinedCodecs.given

final class Quarantined(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[QuarantinedState]:
  def emptyState: QuarantinedState = QuarantinedState()

  def hold(cmd: Hold): Effect[QuarantinedState] =
    if currentState.held then effects.reply(currentState)
    else effects.updateState(QuarantinedState(cmd.source, cmd.why, cmd.sealedBytes, Some(cmd.arrivedAt))).thenReplyState

  def get: ReadOnlyEffect[QuarantinedState] = effects.reply(currentState)

object Quarantined extends KeyValueEntity.Companion[Quarantined, QuarantinedState](
      componentId = ComponentId("quarantined"),
      stateSerializer = QuarantinedCodecs.given_Serializer_QuarantinedState
    ):
  val Undecodable   = "undecodable"
  val BrandMismatch = "brand_mismatch"
  val PersonalValue = "personal_value"
  val Collision     = "collision"
  val PseudonymConflict = "pseudonym_conflict"

  def id(source: String, bytes: Array[Byte]): String =
    s"$source/${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}"

  def entityId(source: String, bytes: Array[Byte]): EntityId = EntityId(id(source, bytes))

  def hold(source: String, why: String, bytes: Array[Byte], at: Instant, key: RegistryKeyBytes): Hold =
    Hold(source, why, Sealing.seal(key, bytes), at)

  def create(context: KeyValueEntityContext) = new Quarantined(context)

  val hold = command("hold")(_.hold)
  val get  = query("get")(_.get)
