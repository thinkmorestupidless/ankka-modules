package io.eitheror.casino.attribution.inputs

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore}
import io.eitheror.casino.attribution.{Binding, Named, Reasons, TagOutcome}
import io.eitheror.casino.attribution.money.Days
import io.eitheror.casino.attribution.networks.Codes
import io.eitheror.casino.attribution.record.{Record, Touches}
import io.eitheror.casino.attribution.settings.SettingsEntity
import io.eitheror.casino.attribution.tag.{Pseudonyms, TagCheck}
import io.eitheror.casino.contract.facts.PlayerRegistered

import java.time.{Instant, ZoneId}
import java.util.concurrent.ConcurrentHashMap

/**
 * A tag or a bonus code resolved into a binding (research R6, R7, R8): the tag verified against what the
 * brand holds (never fetched: FR-023), a legacy tag noted to the transition, a code looked up in the cached
 * code documents and bound through the tag its document names, the pseudonym derived under the network's
 * key from the store. What the brand cannot use leaves the player organic with the reason.
 */
final class Registrar(components: ComponentClient, secrets: SecretStore, sources: TagCheck.Sources, zone: () => ZoneId,
    now: () => Instant = () => Instant.now()):

  /** `player_registered` as the record's registration: the tag binds; else a code does; a code beside a binding tag is a touch. */
  def resolve(f: PlayerRegistered): Record.Register =
    val at     = f.registeredAt
    val tagged = f.btag.map(_.trim).filter(_.nonEmpty).map(tag => tag -> bindingOf(f.playerId, tag, Some(at), f.clickId, at, Binding.Tag))
    val code   = f.bonusCode.map(_.trim).filter(_.nonEmpty)
    tagged match
      case Some((_, Right(b))) =>
        Record.Register(at, f.country, Some(b), None, code.map(Touches.ofCode(_, Touches.Later, at)).toVector)
      case _ =>
        val refused = tagged.collect { case (tag, Left(reason)) => Record.Refusal(tag, reason, at) }
        code.map(c => c -> byCode(f.playerId, c, at, Binding.RegistrationCode)) match
          case Some((_, Right(b)))     => Record.Register(at, f.country, Some(b), refused)
          case Some((c, Left(reason))) => Record.Register(at, f.country, None, refused, Vector(Touches.ofCode(c, reason, at)))
          case None                    => Record.Register(at, f.country, None, refused)

  /**
   * A code's binding, at `at` with `touch`, or what became of it: `unknown`, `unresolved`, or the refusal
   * of the tag its document names (verified without the window: the window is the code's own).
   */
  def byCode(playerId: String, code: String, at: Instant, touch: String): Either[String, Binding] =
    Codes.resolve(code, sources.networks().map(n => n -> sources.documents(n.id)), now()) match
      case Codes.Found(_, tag) => bindingOf(playerId, tag, None, None, at, touch)
      case Codes.Unknown       => Left(Touches.Unknown)
      case Codes.Unresolved    => Left(Touches.Unresolved)

  /** A tag's binding, or the reason it binds nothing. `registeredAt` set applies the attribution window. */
  def bindingOf(playerId: String, tag: String, registeredAt: Option[Instant], clickId: Option[String], at: Instant, touch: String)
      : Either[String, Binding] =
    TagCheck.decide(tag, sources, registeredAt) match
      case TagOutcome.Refused(reason) => Left(reason)
      case TagOutcome.Valid(named, kid, _) =>
        if kid == "legacy" then
          components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.legacySeen).invoke(Days.dayOf(at, zone())): Unit
        pseudonym(named.network, playerId).toRight(Reasons.NetworkNotReady)
          .map(p => Registrar.binding(named, clickId, p, at, touch))

  /** The player's pseudonym for a network, or none while the network has no key (`network_not_ready`). */
  def pseudonym(network: String, playerId: String): Option[String] =
    Registrar.keyOf(secrets, network).map(Pseudonyms.derive(_, playerId))

object Registrar:
  private val keys = ConcurrentHashMap[String, Array[Byte]]()

  /** A network's pseudonym key, read from the store once per process: the key is never rotated. */
  def keyOf(secrets: SecretStore, network: String): Option[Array[Byte]] =
    Option(keys.get(network)).orElse {
      secrets.get(Pseudonyms.secretName(network)).map(Pseudonyms.keyBytes).map { k =>
        keys.putIfAbsent(network, k): Unit
        k
      }
    }

  /** Forgets the cached keys: for a suite that starts a second service in one JVM. */
  def forget(): Unit = keys.clear()

  def binding(named: Named, clickId: Option[String], pseudonym: String, at: Instant, touch: String): Binding =
    Binding(named.network, named.affiliate, named.link, named.campaign, named.subIds, clickId, pseudonym, at, touch)
