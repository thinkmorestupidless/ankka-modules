package io.eitheror.casino.support.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.contract.access.{RoleBags, Routes}
import io.eitheror.casino.support.{Settings, SupportRuntime}
import io.eitheror.casino.support.config.{AgentConfigState, AgentConfiguration}
import io.eitheror.casino.support.conversation.{Conversation, ConversationState}

import java.time.Instant

/**
 * What every endpoint is built with, as engagement's: the service's clients and the authenticator; the settings and
 * clock; and the brand's roles the operator routes decide from (spec 030 amendment E1), by default read from the
 * brand's `players` on first use, or as `bagsOf` gives them.
 */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients, bagsOf: Option[EndpointClients => RoleBags] = None):
  def settings: Settings = SupportRuntime.settings
  def slug: String       = settings.brand
  def now(): Instant     = SupportRuntime.now()

  def components = clients.componentClient

  lazy val bags: RoleBags = bagsOf.fold(BrandRoles.of(clients.services, settings.players, slug))(_(clients))

  /** An operator of this brand whose roles' bags hold `permission` (`Routes.operatorMay`). */
  def may(permission: String): Acl = Routes.operatorMay(slug, permission, bags, authenticate())

  /** The neighbours, called as this service (029 FR-010): what a summary reads before the player is asked. */
  def neighbours = SupportRuntime.neighbours(clients.services)

  def configuration: AgentConfigState = components.forKeyValueEntity(AgentConfiguration.id(slug)).call(AgentConfiguration.get).invoke()

  def conversation(session: String)             = components.forEventSourcedEntity(EntityId(session))
  def conversationState(session: String): ConversationState = conversation(session).call(Conversation.get).invoke()
