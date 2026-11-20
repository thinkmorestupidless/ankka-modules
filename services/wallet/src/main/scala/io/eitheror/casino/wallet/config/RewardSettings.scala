package io.eitheror.casino.wallet.config

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The brand's reward settings as an operator writes them (spec 030 W3, `PUT wallet-config/rewards`):
 * the four switches `rewards` writes through `bet-switches`, replaced whole, the turnover terms left.
 *
 * The original's operator write (`BrandConfigRoutes` `PUT bonus-config`) requires the two switches and
 * the rate table and holds each rate to 0–100; here a rate is a whole percent, a game type is named,
 * and the limit on concurrent awards is a whole number from 1, or `null` (or absent) for no limit, as
 * the original's per-offer limit is blank for none. A refusal names the first field that is wrong, as
 * its path in the body (`contributionRateByGameType.table`).
 */
final case class RewardSettings(
    contributionRateByGameType: Map[String, Long],
    bonusWageringCounts: Boolean,
    partialSplitsAllowed: Boolean,
    maxConcurrentAwards: Option[Int]
)

/** A refused body: the field, as its path in the body, and what it must be. */
final case class RewardSettingsInvalid(field: String, message: String)

object RewardSettings:
  /** More game types than any brand's lobby has; the table is kept whole in one entity's state. */
  val MaxGameTypes: Int = 100
  val MaxGameTypeLength: Int = 64
  val MaxRate: Long = 100L

  private val mapper = ObjectMapper()

  def parse(text: String): Either[RewardSettingsInvalid, RewardSettings] =
    Try(mapper.readTree(text)).toOption.filter(n => n != null && n.isObject) match
      case None       => Left(RewardSettingsInvalid("body", "the body is a JSON object of the reward settings"))
      case Some(body) => read(body)

  private def bool(body: JsonNode, field: String): Either[RewardSettingsInvalid, Boolean] =
    Option(body.get(field)).filter(_.isBoolean).map(_.booleanValue).toRight(RewardSettingsInvalid(field, s"$field is true or false"))

  private def rates(body: JsonNode): Either[RewardSettingsInvalid, Map[String, Long]] =
    val field = "contributionRateByGameType"
    Option(body.get(field)).filter(_.isObject) match
      case None => Left(RewardSettingsInvalid(field, s"$field is an object of game type to a whole percent from 0 to 100"))
      case Some(table) =>
        val entries = table.properties.asScala.toVector.map(e => e.getKey -> e.getValue)
        if entries.size > MaxGameTypes then Left(RewardSettingsInvalid(field, s"$field names at most $MaxGameTypes game types"))
        else
          entries.foldLeft[Either[RewardSettingsInvalid, Map[String, Long]]](Right(Map.empty)) {
            case (Left(e), _) => Left(e)
            case (Right(m), (gameType, v)) =>
              val path = s"$field.$gameType"
              if gameType.trim.isEmpty || gameType.trim != gameType || gameType.length > MaxGameTypeLength then
                Left(RewardSettingsInvalid(path, s"a game type is named, without surrounding spaces, in at most $MaxGameTypeLength characters"))
              else if v == null || !v.isIntegralNumber || !v.canConvertToLong || v.longValue < 0 || v.longValue > MaxRate then
                Left(RewardSettingsInvalid(path, s"a contribution rate is a whole percent from 0 to $MaxRate"))
              else Right(m.updated(gameType, v.longValue))
          }

  private def limit(body: JsonNode): Either[RewardSettingsInvalid, Option[Int]] =
    val field = "maxConcurrentAwards"
    Option(body.get(field)).filterNot(_.isNull) match
      case None                                                        => Right(None)
      case Some(v) if v.isIntegralNumber && v.canConvertToInt && v.intValue >= 1 => Right(Some(v.intValue))
      case Some(_) => Left(RewardSettingsInvalid(field, s"$field is a whole number from 1, or null for no limit"))

  private def read(body: JsonNode): Either[RewardSettingsInvalid, RewardSettings] =
    for
      r <- rates(body)
      b <- bool(body, "bonusWageringCounts")
      p <- bool(body, "partialSplitsAllowed")
      l <- limit(body)
    yield RewardSettings(r, b, p, l)
