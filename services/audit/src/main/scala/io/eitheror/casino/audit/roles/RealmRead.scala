package io.eitheror.casino.audit.roles

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.Wiring
import org.slf4j.LoggerFactory

/**
 * The daily read of the realm (research R8): each role the audit grants, its holders listed, and each
 * holder with no grant recorded once as holding it from outside the audit. A realm that cannot be
 * read is logged and read again the next day.
 */
final class RealmRead(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    RealmRead.run(context.componentClient)
    effects.done()

object RealmRead extends TimedAction.Companion[RealmRead](ComponentId("realm-read")):
  private val log = LoggerFactory.getLogger(classOf[RealmRead])
  val TimerName = "realm-read"

  /** The holdings found outside the audit, per role; a role the realm could not answer is left out. */
  def run(client: ComponentClient): Map[String, Int] =
    val now = Wiring.clock.get().instant()
    Grants.managed(Wiring.settings.get().brands).flatMap { role =>
      Grants.readRole(client, Wiring.realm.get(), role, now) match
        case Right(found) => Some(role -> found)
        case Left(e) =>
          log.warn("the realm read of {}: {}", role, e)
          None
    }.toMap

  def create(context: TimedActionContext) = new RealmRead(context)
  val read = handler("read")(_.run)
