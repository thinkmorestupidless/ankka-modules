package io.eitheror.casino.players

import com.thinkmorestupidless.ankka.sdk.{SecretStore, ServiceClients}
import io.eitheror.casino.players.clients.*
import io.eitheror.casino.players.realm.{KeycloakRealm, Realm}

/**
 * Every outbound seam (plan.md, "Every outbound seam is a trait"), built from what a component or an
 * endpoint is given: its service clients and its secret store. The realm and the geo lookup are one
 * per process, so the admin token and the address cache are shared; the rest are as cheap as their
 * targets. The settings say where the realm, ip-api and `ingest` are; the kit points them at its fakes.
 */
final class Neighbours(services: ServiceClients, secrets: SecretStore):
  private def settings = PlayersRuntime.settings

  def realm: Realm               = Neighbours.realm(secrets)
  def geo: Geo                   = Neighbours.geo
  def rg: Rg                     = Rg.http(services, settings.brand)
  def kyc: Kyc                   = Kyc.http(services, settings.brand)
  def engagement: Engagement     = Engagement.http(services, settings.brand)
  def wallet: Wallet             = Wallet.http(services, settings.brand)
  def erasure: Erasure           = Erasure.http(services)
  def `export`: Export           = Export.http(services)
  def ingest: Option[Ingest]     = settings.ingestUrl.map(Ingest.http)
  def agent: Target              = Target(Neighbours.VendorAgent, "")
  def call: ServiceClients       = services

  /** A secret by its name (`Secrets`), from this store or, on a local run, the environment. */
  def secret(name: String): Either[String, String] = Secrets.read(name, Some(secrets))

object Neighbours:
  val VendorAgent = "vendor-agent"

  @volatile private var realmHeld: Option[Realm] = None
  @volatile private var geoHeld: Option[Geo]     = None

  def realm(secrets: SecretStore): Realm = synchronized {
    realmHeld.getOrElse {
      val s = PlayersRuntime.settings
      val r = KeycloakRealm(s.keycloakUrl, s.realm,
        () => Secrets.read(Secrets.RealmAdmin, Some(secrets)).toOption.flatMap(KeycloakRealm.AdminClient.parse))
      realmHeld = Some(r)
      r
    }
  }

  def geo: Geo = synchronized {
    geoHeld.getOrElse {
      val g = Geo.http(PlayersRuntime.settings.geoUrl)
      geoHeld = Some(g)
      g
    }
  }

  /** Forgets the realm and the lookup, for a kit that starts another service with other settings. */
  def reset(): Unit = synchronized { realmHeld = None; geoHeld = None }
