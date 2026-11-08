package io.eitheror.casino.engagement

import java.time.Duration

/**
 * What the service is told at start: the brand, its neighbours by name, the CRM's hosts when they are not the
 * region's (the stub's, `CRM_TRACK_URL` and `CRM_APP_URL`), and the waits of research R5 and R10. A suite
 * shortens them. The CRM's keys are not here: they are the brand's, in the secret store (R12).
 */
final case class Settings(
    brand: String = "goldvibe",
    players: String = "players",
    rewards: String = "rewards",
    trackUrl: Option[String] = None,
    appUrl: Option[String] = None,
    crmTimeout: Duration = Duration.ofSeconds(10),
    staleness: Duration = Duration.ofHours(24),
    maxConnections: Int = 10000,
    instanceId: String = "local",
    /** How long the send route waits for the first attempt's outcome (contracts/routes.md). */
    requestBound: Duration = Duration.ofSeconds(2),
    attemptBase: Duration = Duration.ofMillis(500),
    attempts: Int = 4,
    heartbeatEvery: Duration = Duration.ofSeconds(15),
    leaseEvery: Duration = Duration.ofSeconds(30),
    revalidateEvery: Duration = Duration.ofSeconds(30),
    recycleAfter: Duration = Duration.ofMinutes(5),
    pollEvery: Duration = Duration.ofSeconds(1),
    sweepEvery: Duration = Duration.ofSeconds(30),
    leaseExpiry: Duration = Duration.ofSeconds(90),
    ticketTtl: Duration = Duration.ofSeconds(30),
    secretsTtl: Duration = Duration.ofSeconds(30)
)

object Settings:
  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    for
      brand   <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                   .toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      track   <- url(env, "CRM_TRACK_URL")
      app     <- url(env, "CRM_APP_URL")
      timeout <- positive(env, "CRM_TIMEOUT", Duration.ofSeconds(10), Duration.ofSeconds)
      stale   <- positive(env, "STALENESS_HOURS", Duration.ofHours(24), Duration.ofHours)
      max     <- positive(env, "MAX_CONNECTIONS", 10000, _.toInt)
    yield Settings(brand = brand, trackUrl = track, appUrl = app, crmTimeout = timeout, staleness = stale,
      maxConnections = max, instanceId = env.get("HOSTNAME").map(_.trim).filter(_.nonEmpty).getOrElse("local"))

  /** Absent or empty is the region's host; anything else must be an http(s) URL. */
  private def url(env: Map[String, String], name: String): Either[String, Option[String]] =
    env.get(name).map(_.trim).filter(_.nonEmpty) match
      case None                                                           => Right(None)
      case Some(u) if u.startsWith("https://") || u.startsWith("http://") => Right(Some(u.stripSuffix("/")))
      case Some(_)                                                        => Left(s"$name: not an http(s) URL")

  /** A positive whole number, or `default` when absent. */
  private def positive[A](env: Map[String, String], name: String, default: A, of: Long => A): Either[String, A] =
    env.get(name).map(_.trim).filter(_.nonEmpty) match
      case None    => Right(default)
      case Some(v) => v.toLongOption.filter(_ > 0).map(of).toRight(s"$name: not a positive whole number")
