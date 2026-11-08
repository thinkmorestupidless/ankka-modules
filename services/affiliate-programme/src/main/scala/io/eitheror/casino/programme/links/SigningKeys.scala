package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/** A signing key's public half, as the key document serves it; the private half is `signing-key/<kid>` in the store. */
final case class SigningKey(kid: String, x: String, madeAt: Instant, retiredAt: Option[Instant] = None):
  def live: Boolean = retiredAt.isEmpty

/** The programme's signing keys (one entity, id `programme`) and when the legacy tag document was withdrawn. */
final case class SigningKeysState(keys: Vector[SigningKey] = Vector.empty, legacyWithdrawnAt: Option[Instant] = None, legacyWithdrawnBy: Option[String] = None,
    revocationsAsOf: Option[Instant] = None):
  def live: Vector[SigningKey] = keys.filter(_.live)
  /** The newest live key: the one that signs. */
  def signing: Option[SigningKey] = live.maxByOption(_.madeAt)

final case class AddKey(kid: String, x: String, at: Instant)
final case class RetireKey(kid: String, at: Instant)
final case class WithdrawLegacy(by: String, at: Instant)

object SigningKeysCodecs:
  given Serializer[SigningKeysState] = ProgrammeCodecs.serializer[SigningKeysState]("signing-keys")
  given Serializer[AddKey]           = ProgrammeCodecs.serializer[AddKey]("add-key")
  given Serializer[RetireKey]        = ProgrammeCodecs.serializer[RetireKey]("retire-key")
  given Serializer[WithdrawLegacy]   = ProgrammeCodecs.serializer[WithdrawLegacy]("withdraw-legacy")
  given Serializer[Instant]          = ProgrammeCodecs.serializer[Instant]("instant")

import SigningKeysCodecs.given

final class SigningKeys(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[SigningKeysState]:
  def emptyState: SigningKeysState = SigningKeysState()

  def add(cmd: AddKey): Effect[SigningKeysState] =
    if currentState.keys.exists(_.kid == cmd.kid) then effects.error(s"a key ${cmd.kid} exists", ErrorCode.Conflict)
    else effects.updateState(currentState.copy(keys = currentState.keys :+ SigningKey(cmd.kid, cmd.x, cmd.at))).thenReplyState

  /** Never the last live key: a tag must always be verifiable. */
  def retire(cmd: RetireKey): Effect[SigningKeysState] =
    val s = currentState
    s.keys.find(_.kid == cmd.kid) match
      case None                    => effects.error(s"no key ${cmd.kid}", ErrorCode.NotFound)
      case Some(k) if !k.live      => effects.reply(s)
      case Some(_) if s.live.size == 1 => effects.error("the last live signing key cannot be retired", ErrorCode.Conflict)
      case Some(_) =>
        effects.updateState(s.copy(keys = s.keys.map(k => if k.kid == cmd.kid then k.copy(retiredAt = Some(cmd.at)) else k))).thenReplyState

  /** The legacy tag document withdrawn, once (FR-026); a repeat changes nothing. */
  def withdrawLegacy(cmd: WithdrawLegacy): Effect[SigningKeysState] =
    if currentState.legacyWithdrawnAt.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(legacyWithdrawnAt = Some(cmd.at), legacyWithdrawnBy = Some(cmd.by))).thenReplyState

  /** The revocation list as of `at` (`RevocationsStamp`, every 300 s). */
  def stamp(at: Instant): Effect[SigningKeysState] = effects.updateState(currentState.copy(revocationsAsOf = Some(at))).thenReplyState

  def get: ReadOnlyEffect[SigningKeysState] = effects.reply(currentState)

object SigningKeys extends KeyValueEntity.Companion[SigningKeys, SigningKeysState](
      componentId = ComponentId("signing-keys"),
      stateSerializer = SigningKeysCodecs.given_Serializer_SigningKeysState
    ):
  val Id = "programme"

  def secretName(kid: String): String = s"signing-key/$kid"

  def create(context: KeyValueEntityContext) = new SigningKeys(context)

  val add            = command("add")(_.add)
  val retire         = command("retire")(_.retire)
  val withdrawLegacy = command("withdraw-legacy")(_.withdrawLegacy)
  val stamp          = command("stamp")(_.stamp)
  val get            = query("get")(_.get)
