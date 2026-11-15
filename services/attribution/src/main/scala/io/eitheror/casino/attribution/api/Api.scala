package io.eitheror.casino.attribution.api

import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.attribution.Settings
import io.eitheror.casino.contract.access.{RoleBags, Routes}

import java.time.Instant

/**
 * What every endpoint is given: the settings, the clients, how a signed-in caller is authenticated
 * (ankka-auth-oidc in a deployment, a stand-in in the suites), the clock, and the brand's roles the operator
 * routes decide from (spec 030 E1: by default read from the brand's `players`).
 */
final class Api(val settings: Settings, val clients: EndpointClients, val authenticate: () => Acl, val now: () => Instant,
    bagsOf: EndpointClients => RoleBags):
  val brand: String = settings.brand
  lazy val bags: RoleBags = bagsOf(clients)

  /** An operator of this brand whose roles' bags hold `permission` (`Routes.operatorMay`). */
  def may(permission: String): Acl = Routes.operatorMay(brand, permission, bags, authenticate())
  def components    = clients.componentClient
  def views         = clients.viewClient
  def secrets       = clients.secrets
