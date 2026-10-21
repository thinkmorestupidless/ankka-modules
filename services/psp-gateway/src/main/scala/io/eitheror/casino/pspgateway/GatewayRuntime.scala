package io.eitheror.casino.pspgateway

import io.eitheror.casino.pspgateway.http.ProviderHttp
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.Provider

import java.security.SecureRandom
import java.time.{Clock, Instant, ZoneOffset}

/**
 * What the endpoints, the deliverer and the timer share that is the service's: the settings, the
 * clock, one `ProviderHttp` (whose windows the status page reads), and the nine providers' packages.
 */
object GatewayRuntime:
  @volatile var settings: Settings    = Settings("http://localhost:9000")
  @volatile var now: () => Instant    = () => Instant.now()
  @volatile private var client: ProviderHttp = ProviderHttp(settings.providerTimeout)
  val random: SecureRandom = SecureRandom()

  def http: ProviderHttp = client

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock
    client = ProviderHttp(s.providerTimeout, new Clock:
      def getZone: java.time.ZoneId = ZoneOffset.UTC
      override def withZone(z: java.time.ZoneId): Clock = this
      override def instant(): Instant = clock())

  val deposits: Map[Provider, Deposits] = Map(
    Provider.DreamzPay    -> dreamzpay.DreamzPay,
    Provider.UnityFinance -> unityfinance.UnityFinance,
    Provider.T365         -> t365.T365,
    Provider.Cozeni       -> cozeni.Cozeni,
    Provider.EuPaymentz   -> eupaymentz.EuPaymentz,
    Provider.Gigadat      -> gigadat.Gigadat,
    Provider.BounceMoney  -> bouncemoney.BounceMoney,
    Provider.Digitarium   -> digitarium.Digitarium,
    Provider.Morphosis    -> morphosis.Morphosis
  )

  val payouts: Map[Provider, Payouts] = Map(
    Provider.UnityFinance -> unityfinance.UnityFinance,
    Provider.Cozeni       -> cozeni.Cozeni,
    Provider.Gigadat      -> gigadat.Gigadat,
    Provider.Morphosis    -> morphosis.Morphosis
  )
