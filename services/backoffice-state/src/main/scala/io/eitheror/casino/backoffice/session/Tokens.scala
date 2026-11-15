package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.*

/** What the token route answers. `rolesChanged` is true once: to the call whose refresh changed the roles. */
enum TokenResult:
  case Answered(accessToken: Secret, expiresAt: Instant, roles: Set[String], rolesChanged: Boolean)
  case Ended
  case Unavailable(why: String)

/**
 * `POST /sessions/{id}/token` (data-model.md): the session's access token while 30 seconds or more remain;
 * else the generation's one refresh, started by this call or joined, waited for at most `wait`, and the
 * token the session then holds.
 */
final class Tokens(components: ComponentClient, now: () => Instant, wait: FiniteDuration = 20.seconds, poll: FiniteDuration = 50.millis):

  def token(session: String): TokenResult =
    val entity = components.forEventSourcedEntity(EntityId(session))
    val asked  = entity.call(Session.token).invoke(TokenAsk(now()))
    asked.state match
      case TokenAnswer.Current => answered(asked, rolesChanged = false)
      case TokenAnswer.Stale =>
        val flow    = components.forWorkflow(SessionRefresh.id(session, asked.generation))
        val started = flow.call(SessionRefresh.start).invoke(StartRefresh(session, asked.generation))
        awaitFlow(() => flow.call(SessionRefresh.status).invoke(), System.nanoTime() + wait.toNanos) match
          case None                                        => TokenResult.Unavailable("the refresh did not finish in time")
          case Some(f) if f.status == RefreshFlow.Revoked => TokenResult.Ended
          case Some(f) if f.status == RefreshFlow.Failed  => TokenResult.Unavailable("the realm did not answer the refresh")
          case Some(f) =>
            val after = entity.call(Session.current).invoke(TokenAsk(now()))
            after.state match
              case TokenAnswer.Current => answered(after, rolesChanged = started && f.rolesChanged)
              case TokenAnswer.Ended   => TokenResult.Ended
              case _                   => TokenResult.Unavailable("the realm answered a token already expired")
      case _ => TokenResult.Ended

  private def answered(a: TokenAnswer, rolesChanged: Boolean): TokenResult =
    (a.accessToken, a.expiresAt) match
      case (Some(t), Some(at)) => TokenResult.Answered(t, at, a.roles, rolesChanged)
      case _                   => TokenResult.Ended

  @tailrec
  private def awaitFlow(read: () => RefreshFlow, deadline: Long): Option[RefreshFlow] =
    val f = read()
    if f.finished then Some(f)
    else if System.nanoTime() >= deadline then None
    else
      Thread.sleep(poll.toMillis.max(1L))
      awaitFlow(read, deadline)
