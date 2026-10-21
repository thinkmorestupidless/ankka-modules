package io.eitheror.casino.merchant.keys

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{MerchantKeyDeactivated, MerchantKeyIssued, PaymentsMerchants, PaymentsMerchantsFact}
import io.eitheror.casino.merchant.MerchantCodecs
import io.eitheror.casino.merchant.hierarchy.{MerchantKey, SubBrand, SubBrandEvent}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** A merchant key's raw value, minted once, and its digest, kept everywhere instead (FR-004). */
object RawKeys:
  private val random = SecureRandom()

  def mint(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    "mk_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** Hex SHA-256 of the raw key: what the index is keyed by and what the facts carry. */
  def digest(raw: String): String =
    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

/** The key index's entry for one digest (FR-004, SC-006): the sub-brand it resolves to in one lookup. */
final case class KeyEntry(digest: String, subBrandId: String = "", keyId: String = "", active: Boolean = false)

final case class IndexKey(subBrandId: String, keyId: String, active: Boolean)

object KeyCodecs:
  given Serializer[KeyEntry] = MerchantCodecs.serializer[KeyEntry]("key-entry")
  given Serializer[IndexKey] = MerchantCodecs.serializer[IndexKey]("index-key")

import KeyCodecs.given

final class MerchantKeyIndex(context: KeyValueEntityContext) extends KeyValueEntity[KeyEntry]:
  def emptyState: KeyEntry = KeyEntry(context.entityId)

  def index(cmd: IndexKey): Effect[KeyEntry] =
    effects.updateState(KeyEntry(context.entityId, cmd.subBrandId, cmd.keyId, cmd.active)).thenReplyState

  def get: ReadOnlyEffect[KeyEntry] = effects.reply(currentState)

object MerchantKeyIndex extends KeyValueEntity.Companion[MerchantKeyIndex, KeyEntry](
      componentId = ComponentId("merchant-key-index"),
      stateSerializer = KeyCodecs.given_Serializer_KeyEntry
    ):
  def create(context: KeyValueEntityContext) = new MerchantKeyIndex(context)
  val index = command("index")(_.index)
  val get   = query("get")(_.get)

/** Keeps the index by digest from the sub-brand's key events (research R2). */
final class KeyIndexer(context: ConsumerContext) extends Consumer[SubBrandEvent, Unit]:
  def onMessage(event: SubBrandEvent): Effect = event match
    case SubBrandEvent.KeyGenerated(key, _) =>
      put(key.digest, IndexKey(messageContext.subject, key.keyId, active = true))
    case SubBrandEvent.KeyDisabled(keyId, _, _) =>
      val sb = context.componentClient.forEventSourcedEntity(EntityId(messageContext.subject)).call(SubBrand.get).invoke()
      sb.keys.find(_.keyId == keyId).fold(effects.ignore())(k => put(k.digest, IndexKey(messageContext.subject, keyId, active = false)))
    case _ => effects.ignore()

  private def put(digest: String, cmd: IndexKey): Effect =
    context.componentClient.forKeyValueEntity(EntityId(digest)).call(MerchantKeyIndex.index).invoke(cmd): Unit
    effects.done()

object KeyIndexer extends Consumer.Companion[KeyIndexer, SubBrandEvent, Unit](
      componentId = ComponentId("key-indexer"),
      source = ChangeSource.eventsOf(SubBrand)
    ):
  def create(ctx: ConsumerContext) = new KeyIndexer(ctx)

/**
 * `merchant_key_issued` and `merchant_key_deactivated` on `payments.merchants` (FR-005), keyed by the
 * key id, never a raw key: `ingest` (spec 006) verifies keys from its own cache of these.
 */
final class MerchantFacts(context: ConsumerContext) extends Consumer[SubBrandEvent, PaymentsMerchantsFact]:
  def onMessage(event: SubBrandEvent): Effect = event match
    case SubBrandEvent.KeyGenerated(key, _) => publish(MerchantKeyIssued(key.keyId, messageContext.subject, key.digest, key.createdAt))
    case SubBrandEvent.KeyDisabled(keyId, _, at) => publish(MerchantKeyDeactivated(keyId, messageContext.subject, at))
    case _                                       => effects.ignore()

  private def publish(fact: PaymentsMerchantsFact): Effect =
    effects.produceAll(Seq(effects.message(fact).withKey(fact.key)
      .withMetadata(Metadata.empty.set("ce-id", s"${messageContext.subject}/${messageContext.sequenceNumber}"))))

object MerchantFacts extends Consumer.Companion[MerchantFacts, SubBrandEvent, PaymentsMerchantsFact](
      componentId = ComponentId("merchant-facts"),
      source = ChangeSource.eventsOf(SubBrand)
    ):
  def create(ctx: ConsumerContext) = new MerchantFacts(ctx)
  override val outputSerializer: Option[Serializer[PaymentsMerchantsFact]] = Some(PaymentsMerchants.serializer)
  override val produceTo: Option[String]                                   = Some(PaymentsMerchants.name)
  override val produces: Option[Publication] = Some(Publication(PaymentsMerchants.name, Some(PaymentsMerchants.contract), None))

/** A sub-brand's active keys, for `ingest`'s refresh (FR-005): one row per sub-brand with keys. */
final case class ActiveKeyRow(subBrandId: String, keys: Vector[MerchantKey])

final class ActiveKeysView extends View[SubBrandEvent, ActiveKeyRow]:
  def onChange(event: SubBrandEvent): Effect =
    val row = rowState.getOrElse(ActiveKeyRow(updateContext.subject, Vector.empty))
    event match
      case SubBrandEvent.KeyGenerated(key, _)    => effects.updateRow(row.copy(keys = row.keys :+ key))
      case SubBrandEvent.KeyDisabled(keyId, _, _) => effects.updateRow(row.copy(keys = row.keys.filterNot(_.keyId == keyId)))
      case _                                     => effects.ignore()

object ActiveKeys extends View.Companion[ActiveKeysView, SubBrandEvent, ActiveKeyRow](
      componentId = ComponentId("active-keys"),
      source = ChangeSource.eventsOf(SubBrand),
      rowSerializer = MerchantCodecs.serializer[ActiveKeyRow]("active-key-row")
    ):
  def create(ctx: ViewComponentContext) = new ActiveKeysView
