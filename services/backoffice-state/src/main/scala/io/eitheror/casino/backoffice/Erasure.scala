package io.eitheror.casino.backoffice

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, ErasureHandler, ErasureReport}
import io.eitheror.casino.backoffice.preferences.Preferences
import io.eitheror.casino.backoffice.session.{End, EndReason, RealmClient, Session, SessionsBySubject}
import org.slf4j.LoggerFactory

import java.time.Instant

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

  def erase(components: ComponentClient, views: ViewClient, realm: RealmClient, subject: String, now: Instant): Erased =
    val ended = SessionsBySubject.open(views, subject).count { row =>
      val o = components.forEventSourcedEntity(EntityId(row.session)).call(Session.end).invoke(End(EndReason.Erased, now))
      o.refreshToken.foreach(t => realm.revoke(t).left.foreach(why => log.warn("erasure: a session's revocation at the realm failed: {}", why)))
      o.ended
    }
    val deleted = components.forKeyValueEntity(EntityId(subject)).call(Preferences.erase).invoke()
    Erased(subject, ended, deleted)

  /** The subject an erasure of `dataSubject` names here, when it is an operator's. */
  def operatorOf(dataSubject: String): Option[String] =
    Option(dataSubject).filter(_.startsWith(Session.SubjectPrefix)).map(_.stripPrefix(Session.SubjectPrefix)).filter(_.nonEmpty)

  val handler: ErasureHandler = ErasureHandler { ctx => subject =>
    operatorOf(subject.value) match
      case None => ErasureReport(0L)
      case Some(operator) =>
        ErasureReport(erase(ctx.client, StateRuntime.views(), StateRuntime.realm, operator, StateRuntime.now()).objects)
  }
