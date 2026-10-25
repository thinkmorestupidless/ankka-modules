package io.eitheror.casino.ingest

import java.time.Duration
import scala.util.Try

/**
 * What the service is told at start (research R13): the service the commands go to, how long a caller
 * waits for it, the rate a sub-brand is served at, the bounds on a body and a request id, and how long a
 * request id and a record are kept. No secret is a setting: the service holds key digests, never a key.
 */
final case class Settings(
    merchantService: String = "merchant",
    answerBound: Duration = Duration.ofSeconds(10),
    rateLimit: Int = 600,
    bodyBound: Int = 65536,
    requestIdBound: Int = 128,
    requestIdRetention: Duration = Duration.ofHours(24),
    recordRetention: Duration = Duration.ofDays(90)
)

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    for
      bound     <- duration(env, "ANSWER_BOUND", d.answerBound)
      rate      <- count(env, "RATE_LIMIT", d.rateLimit)
      body      <- count(env, "BODY_BOUND", d.bodyBound)
      requestId <- count(env, "REQUEST_ID_BOUND", d.requestIdBound)
      idKept    <- duration(env, "REQUEST_ID_RETENTION", d.requestIdRetention)
      kept      <- duration(env, "RECORD_RETENTION", d.recordRetention)
    yield Settings(
      env.get("MERCHANT_SERVICE").map(_.trim).filter(_.nonEmpty).getOrElse(d.merchantService),
      bound, rate, body, requestId, idKept, kept)

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v =>
      parseDuration(v).toRight(s"$name: '$v' is not a duration such as 10s, 24h or 500ms"))

  private def count(env: Map[String, String], name: String, default: Int): Either[String, Int] =
    env.get(name).fold(Right(default))(v =>
      v.trim.toIntOption.filter(_ > 0).toRight(s"$name: '$v' is not a whole number above zero"))

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
