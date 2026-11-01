package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.EndpointClients
import io.eitheror.casino.programme.Settings
import io.eitheror.casino.programme.accounts.{Affiliate, KeycloakAdmin}

import java.time.Instant

/** What every endpoint is given: the settings, the clients, the ACLs, the realm and the clock. */
final class Api(val settings: Settings, val clients: EndpointClients, val access: Access, val realm: KeycloakAdmin, val now: () => Instant):
  def components = clients.componentClient
  def views      = clients.viewClient
  def secrets    = clients.secrets

  /** An account's parent, for the portal's walk: the entity's, since a view may lag a new sub. */
  def parentOf(affiliate: String): Option[String] =
    val s = components.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.get).invoke()
    if s.exists then s.parent else None
