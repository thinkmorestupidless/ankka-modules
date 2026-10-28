package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs
import io.eitheror.casino.attribution.AttributionCodecs.given_Serializer_Instant

import java.time.Instant

/** A network's four document addresses (FR-025); the legacy and code documents are optional. */
final case class Addresses(
    keyDocument: String,
    revocationList: String,
    legacyTagDocument: Option[String] = None,
    codeDocument: Option[String] = None
):
  def of(kind: String): Option[String] = kind match
    case Documents.Kind.Keys        => Some(keyDocument)
    case Documents.Kind.Revocations => Some(revocationList)
    case Documents.Kind.Legacy      => legacyTagDocument
    case Documents.Kind.Codes       => codeDocument
    case _                          => None

/**
 * A network the brand reports to (data-model.md *Network*). The pseudonym key itself lives only in the
 * secret store; this records whether it was made. Removing a network stops new bindings and changes
 * nothing else (FR-025).
 */
final case class NetworkState(
    id: String = "",
    addresses: Option[Addresses] = None,
    roundStream: Boolean = false,
    machine: Option[String] = None,
    pseudonymKeyMade: Boolean = false,
    removed: Option[Instant] = None,
    addedBy: Option[String] = None,
    addedAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None
):
  def exists: Boolean = addresses.isDefined
  def live: Boolean   = exists && removed.isEmpty

final case class AddNetwork(id: String, addresses: Addresses, by: String, at: Instant)
final case class UpdateNetwork(addresses: Addresses, by: String, at: Instant)
final case class RemoveNetwork(by: String, at: Instant)

object NetworkCodecs:
  given Serializer[NetworkState]  = AttributionCodecs.serializer[NetworkState]("network")
  given Serializer[AddNetwork]    = AttributionCodecs.serializer[AddNetwork]("add-network")
  given Serializer[UpdateNetwork] = AttributionCodecs.serializer[UpdateNetwork]("update-network")
  given Serializer[RemoveNetwork] = AttributionCodecs.serializer[RemoveNetwork]("remove-network")

import NetworkCodecs.given

object NetworkIds:
  private val Shape = "[a-z0-9-]{1,64}".r

  /** A network id is 1 to 64 characters of `[a-z0-9-]`. */
  def valid(id: String): Boolean = Shape.matches(id)

  /** A machine is `<organization>/<name>`. */
  def validMachine(m: String): Boolean = m.split('/') match
    case Array(o, n) => o.nonEmpty && n.nonEmpty
    case _           => false

final class Network(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[NetworkState]:
  def emptyState: NetworkState = NetworkState()

  private def https(a: Addresses): Option[String] =
    val all = Vector("keyDocument" -> Some(a.keyDocument), "revocationList" -> Some(a.revocationList),
      "legacyTagDocument" -> a.legacyTagDocument, "codeDocument" -> a.codeDocument)
    all.collectFirst { case (f, Some(u)) if !(u.startsWith("https://") || u.startsWith("http://")) => s"$f: not an http(s) address" }

  def add(cmd: AddNetwork): Effect[NetworkState] =
    if !NetworkIds.valid(cmd.id) then effects.error("id: a network id is 1 to 64 characters of a-z, 0-9 and -", ErrorCode.BadRequest)
    else if currentState.live then effects.error(s"network ${cmd.id} exists", ErrorCode.Conflict)
    else https(cmd.addresses) match
      case Some(e) => effects.error(e, ErrorCode.BadRequest)
      case None =>
        effects.updateState(currentState.copy(id = cmd.id, addresses = Some(cmd.addresses), removed = None,
          addedBy = Some(cmd.by), addedAt = Some(cmd.at), updatedAt = Some(cmd.at))).thenReplyState

  def update(cmd: UpdateNetwork): Effect[NetworkState] =
    if !currentState.exists then effects.error("no such network", ErrorCode.NotFound)
    else https(cmd.addresses) match
      case Some(e) => effects.error(e, ErrorCode.BadRequest)
      case None    => effects.updateState(currentState.copy(addresses = Some(cmd.addresses), updatedAt = Some(cmd.at))).thenReplyState

  def setRoundStream(on: Boolean): Effect[NetworkState] =
    if !currentState.exists then effects.error("no such network", ErrorCode.NotFound)
    else effects.updateState(currentState.copy(roundStream = on)).thenReplyState

  /** The machine admitted to the network's feed; the empty text clears it. */
  def setMachine(m: String): Effect[NetworkState] =
    if !currentState.exists then effects.error("no such network", ErrorCode.NotFound)
    else if m.nonEmpty && !NetworkIds.validMachine(m) then effects.error("machine: <organization>/<name>", ErrorCode.BadRequest)
    else effects.updateState(currentState.copy(machine = Option(m).filter(_.nonEmpty))).thenReplyState

  def pseudonymKeyMade(at: Instant): Effect[NetworkState] =
    if !currentState.exists then effects.error("no such network", ErrorCode.NotFound)
    else if currentState.pseudonymKeyMade then effects.reply(currentState)
    else effects.updateState(currentState.copy(pseudonymKeyMade = true, updatedAt = Some(at))).thenReplyState

  def remove(cmd: RemoveNetwork): Effect[NetworkState] =
    if !currentState.exists then effects.error("no such network", ErrorCode.NotFound)
    else if currentState.removed.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(removed = Some(cmd.at), updatedAt = Some(cmd.at))).thenReplyState

  def get: ReadOnlyEffect[NetworkState] = effects.reply(currentState)

object Network extends KeyValueEntity.Companion[Network, NetworkState](
      componentId = ComponentId("network"),
      stateSerializer = NetworkCodecs.given_Serializer_NetworkState
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  def create(context: KeyValueEntityContext) = new Network(context)

  val add              = command("add")(_.add)
  val update           = command("update")(_.update)
  val setRoundStream   = command("set-round-stream")(_.setRoundStream)
  val setMachine       = command("set-machine")(_.setMachine)
  val pseudonymKeyMade = command("pseudonym-key-made")(_.pseudonymKeyMade)
  val remove           = command("remove")(_.remove)
  val get              = query("get")(_.get)

/** Every network the brand has, for the documents' refresh and the operator's listing. */
final class NetworksView extends View[NetworkState, NetworkState]:
  def onChange(s: NetworkState): Effect = effects.updateRow(s)

object Networks extends View.Companion[NetworksView, NetworkState, NetworkState](
      componentId = ComponentId("networks"),
      source = ChangeSource.stateOf(Network),
      rowSerializer = NetworkCodecs.given_Serializer_NetworkState
    ):
  def create(ctx: ViewComponentContext) = new NetworksView
