package io.eitheror.casino.players

import com.thinkmorestupidless.ankka.sdk.SecretStore

import java.time.Duration
import scala.util.Try

/**
 * What the service is told at start (research R17): the brand, whether it takes real money, where the
 * realm and the player site are, and the bounds and intervals the stories name. No secret is a setting:
 * `Secrets` reads each by name when it is used.
 */
final case class Settings(
    brand: String = "goldvibe",
    realMoney: Boolean = false,
    keycloakUrl: String = "http://127.0.0.1:8080",
    playerSiteBase: String = "http://localhost:3000",
    ingestUrl: Option[String] = None,
    geoUrl: String = "http://ip-api.com",
    realmReconcileInterval: Duration = Duration.ofSeconds(60),
    verificationValidity: Duration = Duration.ofHours(24),
    verificationResendInterval: Duration = Duration.ofMinutes(5),
    mayLogInBound: Duration = Duration.ofMillis(500),
    realmAnswerBound: Duration = Duration.ofMillis(80),
    sessionEndBound: Duration = Duration.ofSeconds(5),
    exportPartBound: Duration = Duration.ofSeconds(10),
    exportRetention: Duration = Duration.ofHours(24),
    registrationBound: Duration = Duration.ofSeconds(5),
    erasureHoldPoll: Duration = Duration.ofHours(1)
):
  /** The brand's realm (contracts/realm.md). */
  def realm: String = s"casino-$brand"

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    for
      brand      <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                      .toRight("CASINO_BRAND: not set; a brand service is deployed by the brands tool")
      realMoney  <- flag(env, "CASINO_REAL_MONEY", d.realMoney)
      reconcile  <- duration(env, "REALM_RECONCILE_INTERVAL", d.realmReconcileInterval)
      validity   <- duration(env, "VERIFICATION_VALIDITY", d.verificationValidity)
      resend     <- duration(env, "VERIFICATION_RESEND_INTERVAL", d.verificationResendInterval)
      mayLogIn   <- duration(env, "MAY_LOG_IN_BOUND", d.mayLogInBound)
      realmBound <- duration(env, "REALM_ANSWER_BOUND", d.realmAnswerBound)
      sessionEnd <- duration(env, "SESSION_END_BOUND", d.sessionEndBound)
      exportPart <- duration(env, "EXPORT_PART_BOUND", d.exportPartBound)
      exportKept <- duration(env, "EXPORT_RETENTION", d.exportRetention)
      register   <- duration(env, "REGISTRATION_BOUND", d.registrationBound)
      holdPoll   <- duration(env, "ERASURE_HOLD_POLL", d.erasureHoldPoll)
    yield Settings(
      brand = brand,
      realMoney = realMoney,
      keycloakUrl = text(env, "KEYCLOAK_URL").getOrElse(d.keycloakUrl).stripSuffix("/"),
      playerSiteBase = text(env, "PLAYER_SITE_BASE").getOrElse(d.playerSiteBase).stripSuffix("/"),
      ingestUrl = text(env, "INGEST_URL").map(_.stripSuffix("/")),
      geoUrl = text(env, "GEO_URL").getOrElse(d.geoUrl).stripSuffix("/"),
      realmReconcileInterval = reconcile,
      verificationValidity = validity,
      verificationResendInterval = resend,
      mayLogInBound = mayLogIn,
      realmAnswerBound = realmBound,
      sessionEndBound = sessionEnd,
      exportPartBound = exportPart,
      exportRetention = exportKept,
      registrationBound = register,
      erasureHoldPoll = holdPoll
    )

  private def text(env: Map[String, String], name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)

  private def flag(env: Map[String, String], name: String, default: Boolean): Either[String, Boolean] =
    text(env, name).fold(Right(default)) {
      case v if v.equalsIgnoreCase("true")  => Right(true)
      case v if v.equalsIgnoreCase("false") => Right(false)
      case v                                => Left(s"$name: '$v' is not true or false")
    }

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v => parseDuration(v).toRight(s"$name: '$v' is not a duration such as 60s, 24h or 500ms"))

  private val Pattern = """(\d+)\s*(ms|s|m|h|d)""".r

  def parseDuration(text: String): Option[Duration] =
    text.trim match
      case Pattern(n, unit) =>
        Try(n.toLong).toOption.filter(_ > 0).map { v =>
          unit match
            case "ms" => Duration.ofMillis(v)
            case "s"  => Duration.ofSeconds(v)
            case "m"  => Duration.ofMinutes(v)
            case "h"  => Duration.ofHours(v)
            case _    => Duration.ofDays(v)
        }
      case _ => None

/**
 * The six secrets (research R17; ankka 038), each read by its name from the service's secret store
 * where one is offered, else, on a local run, from the environment under the name upper-cased with
 * dashes as underscores (`players-realm-admin` is `PLAYERS_REALM_ADMIN`). A secret neither holds is
 * refused naming both. A value is never logged, stored in state or put on a fact.
 */
object Secrets:
  val RealmAdmin    = "players-realm-admin"    // the realm's admin client secret (FR-007)
  val WebhookSecret = "players-webhook-secret" // the realm listener's shared secret (FR-008)
  val RealmSecret   = "players-realm-secret"   // the authenticators' shared secret (FR-013)
  val EmailToken    = "players-email-token"    // signs the verification links (FR-005)
  val LookupKey     = "players-lookup-key"     // keys the duplicate lookup tokens (FR-036)
  val MerchantKey   = "players-merchant-key"   // the brand's cashier key at ingest (FR-043)

  val all: Seq[String] = Seq(RealmAdmin, WebhookSecret, RealmSecret, EmailToken, LookupKey, MerchantKey)

  def variable(name: String): String = name.toUpperCase.replace('-', '_')

  def read(name: String, store: Option[SecretStore], env: Map[String, String] = sys.env): Either[String, String] =
    store.flatMap(s => Try(s.get(name)).toOption.flatten).filter(_.nonEmpty)
      .orElse(env.get(variable(name)).filter(_.nonEmpty))
      .toRight(s"$name: not in the secret store, nor ${variable(name)} in the environment")
