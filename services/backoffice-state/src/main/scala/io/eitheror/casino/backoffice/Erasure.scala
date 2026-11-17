package io.eitheror.casino.backoffice

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, ErasureHandler, ErasureReport}
import io.eitheror.casino.backoffice.preferences.Preferences
import io.eitheror.casino.backoffice.session.{End, EndReason, RealmClient, Session, SessionsBySubject}
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.duration.*

/** What an operator's erasure did here: the sessions it ended and whether it deleted preferences. */
final case class Erased(subject: String, sessionsEnded: Int, preferencesDeleted: Boolean):
  def objects: Long = sessionsEnded.toLong + (if preferencesDeleted then 1L else 0L)

/**
 * An operator's erasure (data-model.md): the subject's open sessions end `erased`, each refresh token
 * revoked at the realm when it answers, and the preferences are deleted. `DELETE /subjects/{subject}`
 * runs it, and so does the erasure handler for a data subject under `operator/`; the installation's
 * erasure also destroys the subject's key, so the name and email every session holds read as erased.
 * Done twice it finds nothing the second time.
 */
object Erasure:
  private val log = LoggerFactory.getLogger("backoffice-state.erasure")

  /**
   * How long the erasure waits for `SessionsBySubject` to show a session signed in just before it, and
   * how many times it looks again. The view follows the sessions' events eventually, so one look can miss
   * a session opened a moment before; each look after the first waits [[Settle]] and ends what is new.
   */
  val Settle: FiniteDuration = 2.seconds
  val Looks: Int             = 3

  def erase(components: ComponentClient, views: ViewClient, realm: RealmClient, subject: String, now: Instant,
      settle: FiniteDuration = Settle): Erased =
    val ended = endAll(() => SessionsBySubject.open(views, subject).map(_.session), settle) { session =>
      val o = components.forEventSourcedEntity(EntityId(session)).call(Session.end).invoke(End(EndReason.Erased, now))
      o.refreshToken.foreach(t => realm.revoke(t).left.foreach(why => log.warn("erasure: a session's revocation at the realm failed: {}", why)))
      o.ended
    }
    val deleted = components.forKeyValueEntity(EntityId(subject)).call(Preferences.erase).invoke()
    Erased(subject, ended, deleted)

  /**
   * Ends every session `open` names, then looks again after `settle`, up to [[Looks]] looks, until a look
   * names none it has not ended; answers how many `end` ended. A session the view shows only after the
   * last look stays open until its access token expires, and its refresh is refused once the realm has
   * no such operator; once the installation destroys the subject's key its tokens read as erased when
   * the entity is next loaded (data-model.md, *Erasure*).
   */
  def endAll(open: () => Vector[String], settle: FiniteDuration, sleep: FiniteDuration => Unit = d => Thread.sleep(d.toMillis))(
      end: String => Boolean): Int =
    @annotation.tailrec
    def look(n: Int, seen: Set[String], ended: Int): Int =
      val fresh = open().filterNot(seen)
      val now   = ended + fresh.count(end)
      if fresh.isEmpty || n >= Looks then now
      else
        sleep(settle)
        look(n + 1, seen ++ fresh, now)
    val first = open()
    val count = first.count(end)
    if Looks <= 1 then count
    else
      sleep(settle)
      look(2, first.toSet, count)

  /** The subject an erasure of `dataSubject` names here, when it is an operator's. */
  def operatorOf(dataSubject: String): Option[String] =
    Option(dataSubject).filter(_.startsWith(Session.SubjectPrefix)).map(_.stripPrefix(Session.SubjectPrefix)).filter(_.nonEmpty)

  val handler: ErasureHandler = ErasureHandler { ctx => subject =>
    operatorOf(subject.value) match
      case None => ErasureReport(0L)
      case Some(operator) =>
        ErasureReport(erase(ctx.client, StateRuntime.views(), StateRuntime.realm, operator, StateRuntime.now()).objects)
  }
