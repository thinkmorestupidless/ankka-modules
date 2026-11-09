package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

import java.time.Instant

/**
 * What a source has delivered (data-model.md `SourceState`), id the source's name: what was stored and
 * passed over, the last arrival and occurrence, and how many records occurred further after their
 * arrival than the skew bound allows. A source whose grant is revoked stops at its last record, and
 * `lastReceived` says since when (FR-012). A stored record is counted once (`Record.keep` says when it
 * is new); a passed-over one is counted as delivered, so a rare redelivery of one counts it again.
 */
final case class SourceCounts(
    stored: Long = 0,
    passedOver: Long = 0,
    skewed: Long = 0,
    lastReceived: Option[Instant] = None,
    lastOccurred: Option[Instant] = None
)

final case class Received(at: Instant, occurredAt: Instant, kept: Boolean, skewed: Boolean)

object SourceStateCodecs:
  given Serializer[SourceCounts] = AuditCodecs.serializer[SourceCounts]("source-counts")
  given Serializer[Received]     = AuditCodecs.serializer[Received]("received")

import SourceStateCodecs.given

final class SourceState(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[SourceCounts]:
  def emptyState: SourceCounts = SourceCounts()

  def received(r: Received): Effect[SourceCounts] =
    val s = currentState
    effects.updateState(s.copy(
      stored = s.stored + (if r.kept then 1 else 0),
      passedOver = s.passedOver + (if r.kept then 0 else 1),
      skewed = s.skewed + (if r.skewed then 1 else 0),
      lastReceived = Some(s.lastReceived.fold(r.at)(l => if r.at.isAfter(l) then r.at else l)),
      // A record from a clock running ahead says nothing of how far behind the consumer is, so it does not
      // move the occurrence the lag is measured from.
      lastOccurred = if r.skewed then s.lastOccurred
        else Some(s.lastOccurred.fold(r.occurredAt)(l => if r.occurredAt.isAfter(l) then r.occurredAt else l))
    )).thenReplyState

  def get: ReadOnlyEffect[SourceCounts] = effects.reply(currentState)

object SourceState extends KeyValueEntity.Companion[SourceState, SourceCounts](
      componentId = ComponentId("source-state"),
      stateSerializer = SourceStateCodecs.given_Serializer_SourceCounts
    ):
  def entityId(source: String): EntityId = EntityId(source)

  def create(context: KeyValueEntityContext) = new SourceState(context)

  val received = command("received")(_.received)
  val get      = query("get")(_.get)
