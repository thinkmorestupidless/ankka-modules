package io.eitheror.casino.payouts

import java.time.Duration

/**
 * What the service is told at start: the brand, and where its two neighbours are (FR-002). The wallet is a
 * service of the brand's project; the gateway is `psp-gateway` in the shared `payments` project, reached
 * over the grant `payments` makes to this service. The waits are the original's (research R8) and the
 * catalogue's refresh R5's; a suite shortens them. There is no secret: both calls are by identity.
 */
final case class Settings(
    brand: String = "goldvibe",
    wallet: String = "wallet",
    paymentsProject: String = "payments",
    gateway: String = "psp-gateway",
    firstPoll: Duration = Duration.ofMinutes(30),
    pollEvery: Duration = Duration.ofMinutes(5),
    refreshEvery: Duration = Duration.ofSeconds(60),
    requestBound: Duration = Duration.ofSeconds(2)
)

object Settings:
  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
      .toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      .map(brand => Settings(brand = brand))
