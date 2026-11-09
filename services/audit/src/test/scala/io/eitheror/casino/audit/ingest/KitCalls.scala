package io.eitheror.casino.audit.ingest

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.audit.registry.*
import io.eitheror.casino.audit.store.{Kept, Record, RecordState}
import io.eitheror.casino.audit.stored.Stored

import scala.collection.mutable

/** `IngestCalls` routed to entity kits by id, one kit per entity, as the runtime routes them. */
final class KitCalls extends IngestCalls:
  val pseudonyms  = mutable.Map.empty[String, KeyValueEntityTestKit[Pseudonym, PseudonymState]]
  val identities  = mutable.Map.empty[String, KeyValueEntityTestKit[Identity, IdentityState]]
  val records     = mutable.Map.empty[String, KeyValueEntityTestKit[Record, RecordState]]
  val quarantined = mutable.Map.empty[String, KeyValueEntityTestKit[Quarantined, QuarantinedState]]
  val sources     = mutable.Map.empty[String, KeyValueEntityTestKit[SourceState, SourceCounts]]

  private def pseudonymOf(id: String) = pseudonyms.getOrElseUpdate(id, KeyValueEntityTestKit.of(Pseudonym, id))
  private def identityOf(id: String)  = identities.getOrElseUpdate(id, KeyValueEntityTestKit.of(Identity, id))
  private def recordOf(key: String)   = records.getOrElseUpdate(key, KeyValueEntityTestKit.of(Record, key))
  private def heldOf(id: String)      = quarantined.getOrElseUpdate(id, KeyValueEntityTestKit.of(Quarantined, id))
  private def sourceOf(name: String)  = sources.getOrElseUpdate(name, KeyValueEntityTestKit.of(SourceState, name))

  def issue(registryId: String): String         = pseudonymOf(registryId).call(Pseudonym.issue).replyValue.pseudonym
  def bind(pseudonym: String, b: Bind): String   = identityOf(pseudonym).call(Identity.bind)(b).replyValue.outcome
  def keep(stored: Stored): Kept                 = recordOf(stored.key).call(Record.keep)(stored).replyValue
  def hold(id: String, h: Hold): Unit            = heldOf(id).call(Quarantined.hold)(h): Unit
  def received(source: String, r: Received): Unit = sourceOf(source).call(SourceState.received)(r): Unit

  def stored: Vector[Stored] = records.values.flatMap(_.call(Record.get).replyValue.stored).toVector
  def counts(source: String): SourceCounts = sourceOf(source).call(SourceState.get).replyValue
  def identity(pseudonym: String): IdentityState = identityOf(pseudonym).call(Identity.get).replyValue
