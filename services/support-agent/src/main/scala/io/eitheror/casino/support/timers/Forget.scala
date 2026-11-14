package io.eitheror.casino.support.timers

import com.thinkmorestupidless.ankka.agent.{SessionMemoryEntity, forSessionMemory}
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.SupportRuntime
import io.eitheror.casino.support.agent.Motoko
import io.eitheror.casino.support.config.AgentConfiguration
import io.eitheror.casino.support.conversation.{At, Conversation, ConversationState, Status}

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/**
 * FR-027's retention (data-model.md *Timers*): `forget-<session>`, set again after every turn at `lastActivity +
 * retentionDays`. When it fires on a conversation untouched for the retention, the session memory is cleared and the
 * conversation recorded as forgotten; one touched since sets itself again at its own due. The retention is the
 * brand's as it is when the timer fires, so a shorter one an operator sets applies at the next firing.
 */
object Forget:
  def timerName(session: String): String = s"forget-$session"

  private def retention(client: ComponentClient): Duration =
    val slug = SupportRuntime.settings.brand
    Duration.ofDays(client.forKeyValueEntity(AgentConfiguration.id(slug)).call(AgentConfiguration.get).invoke().current.retentionDays.toLong)

  /** When the conversation is forgotten if nothing touches it again. */
  def due(client: ComponentClient, s: ConversationState): Instant = s.lastActivity.plus(retention(client))

  /** Sets the session's timer at its due (setting it again replaces it). */
  def schedule(client: ComponentClient, session: String, s: ConversationState): Unit =
    val wait = Duration.between(SupportRuntime.now(), due(client, s)).toMillis.max(0L)
    SupportRuntime.timers().createSingleTimer(timerName(session), wait.millis, ForgetTimer.forget.deferred(session))

  /** The timer's work: forget the conversation if it is untouched for the retention, otherwise set the timer again. */
  def run(client: ComponentClient, session: String): Unit =
    val conversation = client.forEventSourcedEntity(EntityId(session))
    val s            = conversation.call(Conversation.get).invoke()
    val now          = SupportRuntime.now()
    if s.started && s.status != Status.Forgotten then
      if now.isBefore(due(client, s)) then schedule(client, session, s)
      else
        client.forSessionMemory(Motoko.session(session)).call(SessionMemoryEntity.clear).invoke(): Unit
        conversation.call(Conversation.forget).invoke(At(now)): Unit

/** `forget-<session>` due: the payload is the session id. */
final class ForgetTimer(context: TimedActionContext) extends TimedAction:
  def forget(session: String): Effect =
    Forget.run(context.componentClient, session)
    effects.done()

object ForgetTimer extends TimedAction.Companion[ForgetTimer](ComponentId("support-forget-timer")):
  given Serializer[String] = com.thinkmorestupidless.ankka.core.Serializers.string

  def create(context: TimedActionContext) = new ForgetTimer(context)
  val forget = handler("forget")(_.forget)
