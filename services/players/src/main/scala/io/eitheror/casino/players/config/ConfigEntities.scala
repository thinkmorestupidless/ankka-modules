package io.eitheror.casino.players.config

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

import java.time.ZoneId
import java.util.Locale
import scala.util.Try

/**
 * A brand's configuration an operator may change (FR-041; research R12), one key value entity each,
 * under the brand. What an operator cannot change (secrets, the realm, whether the brand takes real
 * money) is the manifest's, not these.
 */
final case class BrandConfig(
    name: String = "",
    legalName: String = "",
    addresses: Map[String, String] = Map.empty,
    playerSiteBase: String = "",
    reportingZone: String = "UTC",
    singleSession: Boolean = false,
    multiAccountDetection: Boolean = true,
    retentionDays: Option[Int] = None,
    features: Map[String, Boolean] = Map.empty
)

/** The currencies the brand offers, and the one a registration without a currency takes (the original's `defaultCurrency`). */
final case class CurrencyConfig(offered: Vector[String] = Vector.empty, defaultCurrency: Option[String] = None)

/** The countries a player may register from or set; empty is every country. */
final case class AllowList(countries: Set[String] = Set.empty):
  def allows(country: String): Boolean = countries.isEmpty || countries(country.toUpperCase(Locale.ROOT))

/** A configuration written, or `invalid_config` naming the field. */
final case class ConfigAnswer[A](config: A, refusal: Option[String] = None, field: Option[String] = None)

object ConfigRules:
  private val IsoCountries = Locale.getISOCountries.toSet

  def brand(c: BrandConfig): Option[String] =
    if Try(ZoneId.of(c.reportingZone)).isFailure then Some("reportingZone")
    else if c.retentionDays.exists(_ <= 0) then Some("retentionDays")
    else None

  def currencies(c: CurrencyConfig): Option[String] =
    if c.offered.exists(code => !knownCurrency(code)) then Some("offered")
    else if c.defaultCurrency.exists(d => !knownCurrency(d) || c.offered.nonEmpty && !c.offered.contains(d)) then Some("defaultCurrency")
    else None

  def allowList(c: AllowList): Option[String] =
    Option.when(c.countries.exists(cc => !IsoCountries(cc)))("countries")

  /** An ISO 4217 code the JDK knows, upper-case. */
  def knownCurrency(code: String): Boolean =
    code.matches("[A-Z]{3}") && Try(java.util.Currency.getInstance(code)).isSuccess

final class BrandConfigEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[BrandConfig]:
  def emptyState: BrandConfig = BrandConfig()

  def set(c: BrandConfig): Effect[ConfigAnswer[BrandConfig]] =
    ConfigRules.brand(c) match
      case Some(field) => effects.reply(ConfigAnswer(currentState, Some("invalid_config"), Some(field)))
      case None        => effects.updateState(c).thenReply(ConfigAnswer(_))

  def get: ReadOnlyEffect[BrandConfig] = effects.reply(currentState)

object BrandConfigEntity
    extends KeyValueEntity.Companion[BrandConfigEntity, BrandConfig](
      componentId = ComponentId("brand-config"),
      stateSerializer = PlayersCodecs.serializer[BrandConfig]("brand-config")
    ):
  given Serializer[BrandConfig]              = PlayersCodecs.serializer[BrandConfig]("brand-config-body")
  given Serializer[ConfigAnswer[BrandConfig]] = PlayersCodecs.serializer[ConfigAnswer[BrandConfig]]("brand-config-answer")

  def create(context: KeyValueEntityContext) = new BrandConfigEntity(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)

final class CurrencyConfigEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CurrencyConfig]:
  def emptyState: CurrencyConfig = CurrencyConfig()

  def set(c: CurrencyConfig): Effect[ConfigAnswer[CurrencyConfig]] =
    val upper = CurrencyConfig(c.offered.map(_.trim.toUpperCase(Locale.ROOT)).distinct, c.defaultCurrency.map(_.trim.toUpperCase(Locale.ROOT)))
    ConfigRules.currencies(upper) match
      case Some(field) => effects.reply(ConfigAnswer(currentState, Some("invalid_config"), Some(field)))
      case None        => effects.updateState(upper).thenReply(ConfigAnswer(_))

  def get: ReadOnlyEffect[CurrencyConfig] = effects.reply(currentState)

object CurrencyConfigEntity
    extends KeyValueEntity.Companion[CurrencyConfigEntity, CurrencyConfig](
      componentId = ComponentId("currency-config"),
      stateSerializer = PlayersCodecs.serializer[CurrencyConfig]("currency-config")
    ):
  given Serializer[CurrencyConfig]               = PlayersCodecs.serializer[CurrencyConfig]("currency-config-body")
  given Serializer[ConfigAnswer[CurrencyConfig]] = PlayersCodecs.serializer[ConfigAnswer[CurrencyConfig]]("currency-config-answer")

  def create(context: KeyValueEntityContext) = new CurrencyConfigEntity(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)

final class AllowListEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[AllowList]:
  def emptyState: AllowList = AllowList()

  def set(c: AllowList): Effect[ConfigAnswer[AllowList]] =
    val upper = AllowList(c.countries.map(_.trim.toUpperCase(Locale.ROOT)))
    ConfigRules.allowList(upper) match
      case Some(field) => effects.reply(ConfigAnswer(currentState, Some("invalid_config"), Some(field)))
      case None        => effects.updateState(upper).thenReply(ConfigAnswer(_))

  def get: ReadOnlyEffect[AllowList] = effects.reply(currentState)

object AllowListEntity
    extends KeyValueEntity.Companion[AllowListEntity, AllowList](
      componentId = ComponentId("allow-list"),
      stateSerializer = PlayersCodecs.serializer[AllowList]("allow-list")
    ):
  given Serializer[AllowList]               = PlayersCodecs.serializer[AllowList]("allow-list-body")
  given Serializer[ConfigAnswer[AllowList]] = PlayersCodecs.serializer[ConfigAnswer[AllowList]]("allow-list-answer")

  def create(context: KeyValueEntityContext) = new AllowListEntity(context)

  val set = command("set")(_.set)
  val get = query("get")(_.get)
