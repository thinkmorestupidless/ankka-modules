package io.eitheror.casino.support.agent

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.support.conversation.{Conversation, End, EndReason, ToolCall}

import java.time.Instant

/**
 * What a tool records on its conversation (research R6): its call and outcome, the approval it ran under, and the
 * conversation's end after a break or an exclusion. A tool cannot read its session, so the handler builds this.
 */
trait Trail:
  def called(c: ToolCall): Unit
  /** The approval the player approved that its tool has not yet answered: the write's key. */
  def approved: Option[String]
  def ended(reason: EndReason): Unit

object Trail:
  def of(components: ComponentClient, session: String, now: () => Instant): Trail = new Trail:
    private def conversation = components.forEventSourcedEntity(EntityId(session))
    def called(c: ToolCall): Unit    = conversation.call(Conversation.toolCalled).invoke(c): Unit
    def approved: Option[String]     = conversation.call(Conversation.get).invoke().approved
    def ended(reason: EndReason): Unit = conversation.call(Conversation.end).invoke(End(reason, now())): Unit
