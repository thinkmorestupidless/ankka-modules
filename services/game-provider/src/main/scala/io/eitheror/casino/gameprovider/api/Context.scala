package io.eitheror.casino.gameprovider.api

import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, HttpProblem, Respond}
import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.api.callbacks.Callbacks
import io.eitheror.casino.gameprovider.catalogue.CatalogueReads
import io.eitheror.casino.gameprovider.clients.*
import io.eitheror.casino.gameprovider.config.{KeyDocuments, ProviderConfigurations}
import io.eitheror.casino.gameprovider.provider.ProviderAdapter
import io.eitheror.casino.gameprovider.provider.st8.St8Adapter

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What the service is told at start: its brand (`CASINO_BRAND`, rendered by the brands tool), whether
 * the brand takes real money (`CASINO_REAL_MONEY`), the provider adapters it has (ST8 only today), how a
 * signed-in caller is authenticated (ankka-auth-oidc in a deployment, a stand-in in tests), and the clock.
 */
final case class Settings(
    brand: String,
    realMoney: Boolean,
    adapters: Vector[ProviderAdapter],
    authenticate: () => Acl,
    keyDocuments: KeyDocuments = KeyDocuments.http(),
    now: () => Instant = () => Instant.now(),
    publicBaseUrl: Option[String] = None
):
  def adapter(provider: String): Option[ProviderAdapter] = adapters.find(_.name == provider)

  /** One configuration cache per secret store, shared by every endpoint, so a write here is seen here at once. */
  private val caches = ConcurrentHashMap[SecretStore, ProviderConfigurations]()
  def configurations(secrets: SecretStore): ProviderConfigurations =
    caches.computeIfAbsent(secrets, s => ProviderConfigurations(s, keyDocuments, now = now))

object Settings:
  def fromEnvironment(authenticate: () => Acl): Settings =
    Settings(
      brand = Routes.brandFromEnvironment,
      realMoney = sys.env.get("CASINO_REAL_MONEY").exists(_.equalsIgnoreCase("true")),
      adapters = Vector(St8Adapter()),
      authenticate = authenticate,
      publicBaseUrl = sys.env.get("CASINO_PUBLIC_URL")
    )

/** The service's clients and the settings, as every endpoint uses them. */
final class Context(val settings: Settings, val clients: EndpointClients):
  val brand: String                    = settings.brand
  def now(): Instant                   = settings.now()
  val components                       = clients.componentClient
  val views                            = clients.viewClient
  val configs: ProviderConfigurations  = settings.configurations(clients.secrets)
  val wallet: WalletClient             = WalletClient(clients.services, brand)
  val players: PlayersClient           = PlayersClient(clients.services, brand)
  val kyc: KycClient                   = KycClient(clients.services, brand)
  val rg: ResponsibleGamblingClient    = ResponsibleGamblingClient(clients.services, brand)
  val rewards: RewardsClient           = RewardsClient(clients.services, brand)
  val catalogue: CatalogueReads        = CatalogueReads.shared(views, brand, components)

  def callbacks(provider: String): Callbacks =
    Callbacks(brand, provider, components, views, wallet, players, configs, settings.now)

  /** The wallet URL a provider is given: the configured one, else this service's brand-segmented callback prefix. */
  def walletUrl(provider: String): String =
    configs.get(provider).flatMap(_.walletUrl)
      .orElse(settings.publicBaseUrl.map(b => s"${b.stripSuffix("/")}/v1/casino/brands/$brand/$provider"))
      .getOrElse(s"/v1/casino/brands/$brand/$provider")

  /** Refuses another brand's `{brand}` (spec 001 FR-005). */
  def ownBrand(segment: String): Unit = Routes.brandSegment(brand, segment).fold(p => throw p, _ => ())

object Context:
  /** A JSON answer under any status, written as the provider's or the frontend's shape is. */
  def json(status: Int, body: JsonNode): Respond[Bytes] = Respond(Bytes("application/json", Json.render(body).getBytes(UTF_8)), status)

  def ok(body: JsonNode): Respond[Bytes] = json(200, body)

  /** A refusal `{error, …fields}`, the original's shape for the launch and the catalogue. */
  def refuse(status: Int, error: String, fields: (String, Any)*): Respond[Bytes] =
    json(status, Json.obj((("error" -> error) +: fields)*))

  def noContent: Respond[Bytes] = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def badRequest(message: String): Nothing = throw HttpProblem.badRequest(message)
