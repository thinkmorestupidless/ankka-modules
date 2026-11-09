package io.eitheror.casino.engagement.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.engagement.{EngagementRuntime, Settings}
import io.eitheror.casino.engagement.config.{Configuration, CrmConfig}
import io.eitheror.casino.engagement.reads.{ProfilesView, SendsView}
import io.eitheror.casino.engagement.send.{Send, SendState}

import java.time.Instant

/** What every endpoint is built with, as rewards': the service's clients and the authenticator; the settings and clock. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients, project: String = ""):
  def settings: Settings = EngagementRuntime.settings
  def slug: String       = settings.brand
  /** This service's project, whose services an identity ACL admits: the brand's is its slug; the integration kit's is `local`. */
  def ownProject: String = if project.isEmpty then slug else project
  def now(): Instant     = EngagementRuntime.now()

  def components = clients.componentClient

  def config: CrmConfig         = components.forKeyValueEntity(EntityId(slug)).call(Configuration.get).invoke()
  def send(key: String)         = components.forWorkflow(EntityId(key))
  def sendState(key: String): SendState = send(key).call(Send.outcome).invoke()

  def sends    = clients.viewClient.forView(SendsView)
  def profiles = clients.viewClient.forView(ProfilesView)
