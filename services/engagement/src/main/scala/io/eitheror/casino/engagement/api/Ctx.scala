package io.eitheror.casino.engagement.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.contract.access.{RoleBags, Routes}
import io.eitheror.casino.engagement.{EngagementRuntime, Settings}
import io.eitheror.casino.engagement.config.{Configuration, CrmConfig}
import io.eitheror.casino.engagement.reads.{ProfilesView, SendsView}
import io.eitheror.casino.engagement.send.{Send, SendState}

import java.time.Instant

/**
 * What every endpoint is built with, as rewards': the service's clients and the authenticator; the settings and clock;
 * and the brand's roles the operator routes decide from (spec 030 amendment E1), by default read from the brand's
 * `players` on first use, or as `bagsOf` gives them.
 */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients, project: String = "",
    bagsOf: Option[EndpointClients => RoleBags] = None):
  def settings: Settings = EngagementRuntime.settings
  def slug: String       = settings.brand
  /** This service's project, whose services an identity ACL admits: the brand's is its slug; the integration kit's is `local`. */
  def ownProject: String = if project.isEmpty then slug else project
  def now(): Instant     = EngagementRuntime.now()

  def components = clients.componentClient

  lazy val bags: RoleBags = bagsOf.fold(BrandRoles.of(clients.services, settings.players, slug))(_(clients))

  /** An operator of this brand whose roles' bags hold `permission` (`Routes.operatorMay`). */
  def may(permission: String): Acl = Routes.operatorMay(slug, permission, bags, authenticate())

  def config: CrmConfig         = components.forKeyValueEntity(EntityId(slug)).call(Configuration.get).invoke()
  def send(key: String)         = components.forWorkflow(EntityId(key))
  def sendState(key: String): SendState = send(key).call(Send.outcome).invoke()

  def sends    = clients.viewClient.forView(SendsView)
  def profiles = clients.viewClient.forView(ProfilesView)
