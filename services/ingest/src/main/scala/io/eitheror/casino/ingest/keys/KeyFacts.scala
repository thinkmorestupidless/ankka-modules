package io.eitheror.casino.ingest.keys

import com.thinkmorestupidless.ankka.core.{EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{MerchantKeyDeactivated, MerchantKeyIssued, PaymentsMerchants, PaymentsMerchantsFact}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * The key index from `merchant`'s facts (FR-004, research R3): `merchant_key_issued` issues the key under
 * its id, `merchant_key_deactivated` deactivates it, and what `Key` decided is written under the digest. Read from the topic's start, so a new deployment sees
 * every key already published; at least once, and both commands are idempotent. A fact this service cannot
 * read is logged and skipped, never thrown: a family added to the topic later must not stop the index.
 */
final class KeyFacts(context: ConsumerContext) extends Consumer[Option[PaymentsMerchantsFact], Unit]:
  def onMessage(fact: Option[PaymentsMerchantsFact]): Effect =
    val keys = context.componentClient
    fact match
      case Some(MerchantKeyIssued(keyId, subBrand, digest, at)) =>
        KeyIndex.record(keys, keyId, keys.forKeyValueEntity(EntityId(keyId)).call(Key.issue).invoke(Issue(digest, subBrand, at)))
        effects.done()
      case Some(MerchantKeyDeactivated(keyId, _, at)) =>
        KeyIndex.record(keys, keyId, keys.forKeyValueEntity(EntityId(keyId)).call(Key.deactivate).invoke(Deactivate(at)))
        effects.done()
      case None =>
        KeyFacts.log.warn("a payments.merchants fact this service cannot read was skipped (subject {})", messageContext.subject)
        effects.ignore()

object KeyFacts extends Consumer.Companion[KeyFacts, Option[PaymentsMerchantsFact], Unit](
      componentId = com.thinkmorestupidless.ankka.core.ComponentId("key-facts"),
      // Naming the topic's contract: ankka refuses to start a component reading a topic whose project
      // declares a contract the component does not name.
      source = ChangeSource.fromTopic(PaymentsMerchants.name, LenientFacts.serializer, StartFrom.Earliest,
        TopicOptions(Some(PaymentsMerchants.contract), None, false))
    ):
  private val log = LoggerFactory.getLogger(classOf[KeyFacts])

  def create(ctx: ConsumerContext) = new KeyFacts(ctx)

/** The topic's serializer, answering none for what it cannot read. */
object LenientFacts:
  val serializer: Serializer[Option[PaymentsMerchantsFact]] = new Serializer[Option[PaymentsMerchantsFact]]:
    private val facts = PaymentsMerchants.serializer
    def manifest: String = facts.manifest
    def toBytes(value: Option[PaymentsMerchantsFact]): Array[Byte] =
      value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[PaymentsMerchantsFact] = Try(facts.fromBytes(bytes)).toOption
