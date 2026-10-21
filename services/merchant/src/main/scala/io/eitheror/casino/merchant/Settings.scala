package io.eitheror.casino.merchant

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.merchant.Json.*

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A brand whose hierarchy this service seeds (FR-003, research R15): its slug and display name. */
final case class SeededBrand(slug: String, name: String)

/**
 * What the service is told at start (research R19), rendered by the brands tool from the shared
 * manifest's pin: the brands to seed, the payment's deadline, how long a capture may stay
 * uncredited before `/status` counts it, how long a payment's customer fields are kept, how often the
 * realm's enabled flags are mirrored, the realm, the invitation provider's region and the backoffice
 * address an invitation links to. Secrets are not settings: they are names in the secret store.
 */
final case class Settings(
    brands: Vector[SeededBrand] = Vector.empty,
    paymentDeadline: Duration = Duration.ofMinutes(30),
    uncreditedBound: Duration = Duration.ofMinutes(15),
    customerRetention: Duration = Duration.ofDays(90),
    operatorSyncInterval: Duration = Duration.ofSeconds(60),
    keycloakUrl: String = "http://localhost:8180",
    operatorsRealm: String = "eitheror",
    engagementRegion: String = "eu",
    operatorConsoleUrl: String = "http://localhost:3001"
)

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    for
      brands    <- env.get("MERCHANT_BRANDS").fold(Right(Vector.empty))(brandsFrom)
      deadline  <- duration(env, "PAYMENT_DEADLINE", d.paymentDeadline)
      bound     <- duration(env, "UNCREDITED_BOUND", d.uncreditedBound)
      retention <- duration(env, "CUSTOMER_RETENTION", d.customerRetention)
      sync      <- duration(env, "OPERATOR_SYNC_INTERVAL", d.operatorSyncInterval)
    yield Settings(
      brands, deadline, bound, retention, sync,
      env.getOrElse("KEYCLOAK_URL", d.keycloakUrl),
      env.getOrElse("OPERATORS_REALM", d.operatorsRealm),
      env.getOrElse("ENGAGEMENT_REGION", d.engagementRegion),
      env.getOrElse("OPERATOR_CONSOLE_URL", d.operatorConsoleUrl)
    )

  /** `MERCHANT_BRANDS`: a JSON list of `{slug, name}`. */
  def brandsFrom(text: String): Either[String, Vector[SeededBrand]] =
    Json.parse(text).filter(_.isArray) match
      case None => Left("MERCHANT_BRANDS is not a JSON list of {slug, name}")
      case Some(arr) =>
        val items = arr.elements.asScala.toVector
        val read  = items.map {
          case o: ObjectNode => o.text("slug").map(s => SeededBrand(s, o.text("name").getOrElse(s)))
          case _             => None
        }
        if read.forall(_.isDefined) then Right(read.flatten) else Left("MERCHANT_BRANDS: every entry needs a slug")

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v => parseDuration(v).toRight(s"$name: '$v' is not a duration such as 30m, 15m, 90d, 60s or 500ms"))

  private val Pattern = """(\d+)\s*(ms|s|m|h|d)""".r

  def parseDuration(text: String): Option[Duration] =
    text.trim match
      case Pattern(n, unit) =>
        Try(n.toLong).toOption.map { v =>
          unit match
            case "ms" => Duration.ofMillis(v)
            case "s"  => Duration.ofSeconds(v)
            case "m"  => Duration.ofMinutes(v)
            case "h"  => Duration.ofHours(v)
            case _    => Duration.ofDays(v)
        }
      case _ => None

  /** A duration as the settings write it, for `/status`'s `bound`. */
  def render(d: Duration): String =
    if d.toMillis % 86_400_000L == 0 then s"${d.toDays}d"
    else if d.toMillis % 3_600_000L == 0 then s"${d.toHours}h"
    else if d.toMillis % 60_000L == 0 then s"${d.toMinutes}m"
    else if d.toMillis % 1000L == 0 then s"${d.toSeconds}s"
    else s"${d.toMillis}ms"
