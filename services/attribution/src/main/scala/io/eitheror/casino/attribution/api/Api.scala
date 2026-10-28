package io.eitheror.casino.attribution.api

import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.attribution.Settings

import java.time.Instant

/**
 * What every endpoint is given: the settings, the clients, how a signed-in caller is authenticated
 * (ankka-auth-oidc in a deployment, a stand-in in the suites) and the clock.
 */
final class Api(val settings: Settings, val clients: EndpointClients, val authenticate: () => Acl, val now: () => Instant):
  val brand: String = settings.brand
  def components    = clients.componentClient
  def views         = clients.viewClient
  def secrets       = clients.secrets
