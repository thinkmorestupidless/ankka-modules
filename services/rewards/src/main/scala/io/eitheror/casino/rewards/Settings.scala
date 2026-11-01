package io.eitheror.casino.rewards

import java.time.Duration

/**
 * What the service is told at start: the brand, its neighbours by name, and the programme's code document
 * (research R13; `none` reads no document). The provider's poll is the original's (R8); the document's
 * refresh and staleness R13's. A suite shortens them. There is no secret: every call is by identity.
 */
final case class Settings(
    brand: String = "goldvibe",
    wallet: String = "wallet",
    gameProvider: String = "game-provider",
    players: String = "players",
    codeDocumentUrl: Option[String] = None,
    pollEvery: Duration = Duration.ofSeconds(30),
    pollLimit: Int = 50,
    codesEvery: Duration = Duration.ofMinutes(5),
    codesStaleAfter: Duration = Duration.ofMinutes(15),
    requestBound: Duration = Duration.ofSeconds(2)
)

object Settings:
  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    for
      brand <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                 .toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      url   <- codeDocument(env.get("REWARDS_CODE_DOCUMENT_URL"))
    yield Settings(brand = brand, codeDocumentUrl = url)

  /** Absent, empty or `none` is no document; anything else must be an http(s) URL. */
  private def codeDocument(value: Option[String]): Either[String, Option[String]] =
    value.map(_.trim).filter(v => v.nonEmpty && v != "none") match
      case None                                                        => Right(None)
      case Some(u) if u.startsWith("https://") || u.startsWith("http://") => Right(Some(u))
      case Some(_) => Left("REWARDS_CODE_DOCUMENT_URL: not an http(s) URL, nor `none`")
