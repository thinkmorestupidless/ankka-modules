package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.KycRuntime
import io.eitheror.casino.kyc.policy.PolicyEntity
import io.eitheror.casino.kyc.record.{Category, Completeness, KycRecord, ProfileChanged, ProfileField}
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.util.Try

/**
 * What this service reads of other services' facts. Each reads its topic from `StartFrom.Latest` through a
 * lenient serializer: a fact it cannot read is logged by its subject, never its payload, and skipped.
 */
object Read:
  private val log = LoggerFactory.getLogger("io.eitheror.casino.kyc.consumers")

  def lenient[F <: Fact](topic: Topic[F]): Serializer[Option[F]] = new Serializer[Option[F]]:
    private val facts = topic.serializer
    def manifest: String                         = facts.manifest
    def toBytes(value: Option[F]): Array[Byte]   = value.fold(Array.emptyByteArray)(facts.toBytes)
    def fromBytes(bytes: Array[Byte]): Option[F] = Try(facts.fromBytes(bytes)).toOption

  def from[F <: Fact](topic: Topic[F]): ChangeSource[Option[F]] =
    ChangeSource.fromTopic(topic.name, lenient(topic), StartFrom.Latest, TopicOptions(Some(topic.contract), None, false))

  def unreadable(topic: String, subject: String): Unit =
    log.warn("a {} fact this service cannot read was skipped (subject {})", topic, subject)

/**
 * `player_profile_updated` into `ProfileChanged` (R11): which of the eight fields are present, the day the
 * player turns eighteen, and whether a required field changed. No field's value is kept or logged. The change is
 * timed by the fact and the status by when it is read, so a fact read late or twice cannot undo a later verification.
 */
final class ProfileConsumer(client: ComponentClient) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case Some(f: PlayerProfileUpdated) =>
      val policy = client.forEventSourcedEntity(EntityId(KycRuntime.settings.brand)).call(PolicyEntity.get).invoke()
      client.forEventSourcedEntity(EntityId(f.playerId)).call(KycRecord.profileChanged)
        .invoke(ProfileConsumer.command(f, policy.required, KycRuntime.now())): Unit
      effects.done()
    case Some(_) => effects.ignore()
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()

object ProfileConsumer extends Consumer.Companion[ProfileConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("kyc-profile-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new ProfileConsumer(ctx.componentClient)

  /**
   * A changed field by the fact's name or the original's (R11: `givenName` is the original's `firstName`),
   * so a change is seen whichever `players` names it by.
   */
  private val Names: Map[String, ProfileField] =
    Map("givenName" -> ProfileField.FirstName, "familyName" -> ProfileField.LastName, "telephone" -> ProfileField.Phone,
      "addressLine" -> ProfileField.AddressLine1, "postCode" -> ProfileField.PostalCode) ++ ProfileField.values.map(f => f.wire -> f)

  def command(f: PlayerProfileUpdated, policyRequired: Vector[Category], at: Instant): ProfileChanged =
    changed(f.playerId, Vector(f.givenName, f.familyName, f.dateOfBirth, f.telephone, f.addressLine, f.city, f.postCode).map(_.map(_.reveal)),
      f.country, f.changedFields.exists(Names.contains), policyRequired, at, f.occurredAt)

  /** The profile `player_registered` carries, as a first profile: no required field changed; none when it carries no field. */
  def registered(f: PlayerRegistered, policyRequired: Vector[Category], at: Instant): Option[ProfileChanged] =
    val c = changed(f.playerId, Vector(f.givenName, f.familyName, f.dateOfBirth, f.telephone, f.addressLine, f.city, f.postCode).map(_.map(_.reveal)),
      f.country, false, policyRequired, at, f.occurredAt)
    Option.when(c.present.nonEmpty)(c)

  /** The seven personal fields in `ProfileField`'s order before the country. */
  private def changed(playerId: String, personal: Vector[Option[String]], country: Option[String], requiredChanged: Boolean,
      policyRequired: Vector[Category], at: Instant, changedAt: Instant): ProfileChanged =
    val fields = Vector(ProfileField.FirstName, ProfileField.LastName, ProfileField.DateOfBirth, ProfileField.Phone,
      ProfileField.AddressLine1, ProfileField.City, ProfileField.PostalCode)
    val presence = Completeness.facts(fields.zip(personal).toMap + (ProfileField.Country -> country))
    ProfileChanged(playerId, presence.present, presence.adultFrom, requiredChanged, policyRequired, at, Some(changedAt))
