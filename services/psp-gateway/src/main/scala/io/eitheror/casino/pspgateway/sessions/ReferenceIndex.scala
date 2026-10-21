package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.pspgateway.GatewayCodecs

import scala.concurrent.duration.*

/** Which session a provider's own reference names: `deposit` or `payout`, and its key. */
final case class Indexed(kind: String, key: String)

object Indexed:
  val Deposit = "deposit"
  val Payout  = "payout"

/**
 * A provider's reference to a session where the provider does not echo ours (research R4): T365's
 * dashless `orderId`, Morphosis's deposit and payout tokens. Key value under `<provider>/<reference>`,
 * written by the initiate before the provider is called and never updated; it expires after 400
 * days, past any provider's redelivery and every dispute window.
 */
final case class IndexState(entry: Option[Indexed] = None)

final case class Put(entry: Indexed)

object IndexCodecs:
  given Serializer[IndexState]     = GatewayCodecs.serializer[IndexState]("index-state")
  given Serializer[Put]            = GatewayCodecs.serializer[Put]("index-put")
  given Serializer[Option[Indexed]] = GatewayCodecs.serializer[Option[Indexed]]("index-entry")

import IndexCodecs.given

final class ReferenceIndex(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[IndexState]:

  def emptyState: IndexState = IndexState()

  /** Once: a second put, of anything, keeps the first. */
  def put(cmd: Put): Effect[IndexState] =
    if currentState.entry.isDefined then effects.reply(currentState)
    else effects.updateState(IndexState(Some(cmd.entry))).expireAfter(ReferenceIndex.Lifetime).thenReplyState

  def get: ReadOnlyEffect[Option[Indexed]] = effects.reply(currentState.entry)

object ReferenceIndex extends KeyValueEntity.Companion[ReferenceIndex, IndexState](
      componentId = ComponentId("reference-index"),
      stateSerializer = IndexCodecs.given_Serializer_IndexState
    ):
  def create(context: KeyValueEntityContext) = new ReferenceIndex(context)

  val Lifetime: FiniteDuration = 400.days

  def key(provider: Provider, reference: String): String = s"${provider.path}/$reference"

  val put = command("put")(_.put)
  val get = query("get")(_.get)
