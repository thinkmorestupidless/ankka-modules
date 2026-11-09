package io.eitheror.casino.support.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import io.eitheror.casino.support.{Settings, SupportRuntime}
import io.eitheror.casino.support.config.{AgentConfigState, AgentConfiguration}
import io.eitheror.casino.support.conversation.{Conversation, ConversationState}

import java.time.Instant

/** What every endpoint is built with, as engagement's: the service's clients and the authenticator; the settings and clock. */
final class Ctx(val authenticate: () => Acl, val clients: EndpointClients):
  def settings: Settings = SupportRuntime.settings
  def slug: String       = settings.brand
  def now(): Instant     = SupportRuntime.now()

  def components = clients.componentClient

  /** The neighbours, called as this service (029 FR-010): what a summary reads before the player is asked. */
  def neighbours = SupportRuntime.neighbours(clients.services)

  def configuration: AgentConfigState = components.forKeyValueEntity(AgentConfiguration.id(slug)).call(AgentConfiguration.get).invoke()

  def conversation(session: String)             = components.forEventSourcedEntity(EntityId(session))
  def conversationState(session: String): ConversationState = conversation(session).call(Conversation.get).invoke()
