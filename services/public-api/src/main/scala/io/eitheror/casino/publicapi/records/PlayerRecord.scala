package io.eitheror.casino.publicapi.records

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, Personal, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean

import java.time.Instant

/**
 * A player (data-model.md `PlayerRecord`): registration, statuses, profile, and the identifying fields as `Personal`
 * under `player/<id>`. A fact before `player_registered` makes the record with what it carries and no `registeredAt`,
 * and it is not listed until registration arrives.
 */
final case class PlayerState(
    id: String = "",
    registeredAt: Option[Instant] = None,
    status: String = "active",
    kycStatus: String = "unverified",
    country: Option[String] = None,
    currency: Option[String] = None,
    language: Option[String] = None,
    testAccount: Boolean = false,
    email: Option[Personal[String]] = None,
    firstName: Option[Personal[String]] = None,
    lastName: Option[Personal[String]] = None,
    dateOfBirth: Option[Personal[String]] = None,
    phone: Option[Personal[String]] = None,
    addressLine1: Option[Personal[String]] = None,
    city: Option[Personal[String]] = None,
    stateProv: Option[Personal[String]] = None,
    postalCode: Option[Personal[String]] = None,
    erased: Boolean = false,
    applied: Vector[String] = Vector.empty
):
  /** Every identifying field read as erased: the erasure route's, and what a fact read again after it keeps. */
  def withErased: PlayerState =
    val e = Personal.Erased(Records.subject(id)): Personal[String]
    def x(f: Option[Personal[String]]) = f.map(_ => e)
    copy(erased = true, email = x(email), firstName = x(firstName), lastName = x(lastName), dateOfBirth = x(dateOfBirth), phone = x(phone),
      addressLine1 = x(addressLine1), city = x(city), stateProv = x(stateProv), postalCode = x(postalCode))

object PlayerFold:
  import Records.personal as p

  /** The record after `fact`; a family that changes no served field leaves it as it was. */
  def apply(s0: PlayerState, fact: f.CasinoPlayersFact): PlayerState =
    val next = folded(s0, fact)
    if s0.erased then next.withErased else next

  private def folded(s0: PlayerState, fact: f.CasinoPlayersFact): PlayerState =
    val s = s0.copy(id = fact.playerId)
    def pid = fact.playerId
    fact match
      case x: f.PlayerRegistered =>
        s.copy(registeredAt = Some(x.registeredAt), country = x.country.orElse(s.country), currency = x.currency.orElse(s.currency),
          language = x.language.orElse(s.language), testAccount = x.testAccount.getOrElse(s.testAccount),
          email = p(pid, x.email).orElse(s.email), firstName = p(pid, x.givenName), lastName = p(pid, x.familyName),
          dateOfBirth = p(pid, x.dateOfBirth), phone = p(pid, x.telephone), addressLine1 = p(pid, x.addressLine), city = p(pid, x.city),
          stateProv = p(pid, x.stateProvince), postalCode = p(pid, x.postCode))
      case x: f.PlayerEmailChanged => s.copy(email = p(pid, Some(x.email)))
      case x: f.PlayerProfileUpdated =>
        // The fact carries the whole profile (contracts/fields.md), so it replaces it.
        s.copy(firstName = p(pid, x.givenName), lastName = p(pid, x.familyName), dateOfBirth = p(pid, x.dateOfBirth),
          phone = p(pid, x.telephone), addressLine1 = p(pid, x.addressLine), city = p(pid, x.city), postalCode = p(pid, x.postCode),
          stateProv = p(pid, x.stateProvince), country = x.country.orElse(s.country), language = x.language.orElse(s.language))
      case x: f.PlayerTestAccountChanged => s.copy(testAccount = x.testAccount)
      case x: f.PlayerSuspended          => s.copy(status = x.newValue)
      case x: f.PlayerReinstated         => s.copy(status = x.newValue)
      case x: f.PlayerSelfExcluded       => s.copy(status = x.newValue)
      case x: f.PlayerClosed             => s.copy(status = x.newValue)
      case x: f.PlayerReopened           => s.copy(status = x.newValue)
      case x: f.PlayerKycVerified        => s.copy(kycStatus = x.newValue)
      case x: f.PlayerKycRejected        => s.copy(kycStatus = x.newValue)
      case x: f.PlayerKycPending         => s.copy(kycStatus = x.newValue)
      case _                             => s0

  /** Whether a family changes this record at all, so the intake does not touch a record for every login. */
  def changes(fact: f.CasinoPlayersFact): Boolean = fact match
    case _: f.PlayerRegistered | _: f.PlayerEmailChanged | _: f.PlayerProfileUpdated | _: f.PlayerTestAccountChanged |
        _: f.PlayerSuspended | _: f.PlayerReinstated | _: f.PlayerSelfExcluded | _: f.PlayerClosed | _: f.PlayerReopened |
        _: f.PlayerKycVerified | _: f.PlayerKycRejected | _: f.PlayerKycPending => true
    case _ => false

final case class PlayerFact(factId: String, fact: f.CasinoPlayersFact)
final case class ErasePlayer(playerId: String)

final class PlayerRecord(@scala.annotation.unused context: KeyValueEntityContext) extends RecordEntity[PlayerState]:
  def emptyState: PlayerState                                   = PlayerState()
  protected def applied(s: PlayerState): Vector[String]        = s.applied
  protected def remembered(s: PlayerState, ids: Vector[String]) = s.copy(applied = ids)

  def record(c: PlayerFact): Effect[Boolean] = once(c.factId)(PlayerFold(_, c.fact))

  /** The player's identifying fields erased here (FR-025): absent with every key from now on, facts read again included. */
  def erase(c: ErasePlayer): Effect[Boolean] =
    val next = currentState.copy(id = c.playerId).withErased
    if next == currentState then effects.reply(false) else effects.updateState(next).thenReply(_ => true)
  def get: ReadOnlyEffect[PlayerState]       = effects.reply(currentState)

object PlayerRecord extends KeyValueEntity.Companion[PlayerRecord, PlayerState](
      componentId = ComponentId("player-record"),
      stateSerializer = PublicApiCodecs.serializer[PlayerState]("player-record")
    ):
  given Serializer[PlayerFact]  = Serializer.json[PlayerFact]("public-api.player-fact")(using JsonCodecMaker.make[PlayerFact])
  given Serializer[PlayerState] = PublicApiCodecs.serializer[PlayerState]("player-reply")
  given Serializer[ErasePlayer] = PublicApiCodecs.serializer[ErasePlayer]("erase-player")

  def create(context: KeyValueEntityContext) = new PlayerRecord(context)

  val record = command("record")(_.record)
  val erase  = command("erase")(_.erase)
  val get    = query("get")(_.get)
