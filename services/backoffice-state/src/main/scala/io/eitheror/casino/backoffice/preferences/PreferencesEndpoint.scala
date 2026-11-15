package io.eitheror.casino.backoffice.preferences

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import io.eitheror.casino.backoffice.{Erasure, StateRuntime}
import io.eitheror.casino.backoffice.Answers.*
import io.eitheror.casino.contract.access.Routes

import scala.util.control.NonFatal

/** `/preferences/{subject}` (contracts/routes.md, FR-038), served to the brand's `backoffice` alone. */
final class PreferencesEndpoint(clients: EndpointClients) extends HttpEndpoint("/preferences"):
  val acl: Acl = Routes.serviceOf("backoffice")

  private def entity(subject: String) = clients.componentClient.forKeyValueEntity(EntityId(subject))

  get("/{subject}") { (subject: String) =>
    if !validSubject(subject) then badSubject else json(200, PreferencesEndpoint.wire(entity(subject).call(Preferences.get).invoke()))
  }

  putBody("/{subject}") { (subject: String, text: String) =>
    if !validSubject(subject) then badSubject
    else
      PreferencesRules.parse(text) match
        case Left(r) => refusal(400, r.code, r.message)
        case Right(w) =>
          try json(200, PreferencesEndpoint.wire(entity(subject).call(Preferences.write).invoke(w)))
          catch case e: CommandError => refusal(400, e.message.takeWhile(_ != ':'), e.message.dropWhile(_ != ':').drop(1).trim)
  }

  private def badSubject: Reply = refusal(400, "bad_subject", "a subject is the realm's subject of one operator")

object PreferencesEndpoint:
  /** `{theme, worklistKinds, onlineNotices, jobs}`; `worklistKinds` is null while the operator keeps the default. */
  def wire(s: PreferencesState): JsonNode =
    val o = obj().put("theme", s.theme).put("onlineNotices", s.onlineNotices)
    s.worklistKinds match
      case None        => o.putNull("worklistKinds")
      case Some(kinds) => val a = o.putArray("worklistKinds"); kinds.toVector.sorted.foreach(a.add)
    val jobs = o.putArray("jobs")
    s.jobs.foreach(j => jobs.addObject().put("reference", j.reference).put("kind", j.kind).put("startedAt", j.startedAt.toString))
    o

/** `DELETE /subjects/{subject}`: an operator's erasure, its sessions ended and its preferences deleted (data-model.md). */
final class SubjectsEndpoint(clients: EndpointClients) extends HttpEndpoint("/subjects"):
  val acl: Acl = Routes.serviceOf("backoffice")

  private val log = org.slf4j.LoggerFactory.getLogger("backoffice-state.erasure")

  delete("/{subject}") { (subject: String) =>
    if !validSubject(subject) then refusal(400, "bad_subject", "a subject is the realm's subject of one operator")
    else
      try
        val e = Erasure.erase(clients.componentClient, clients.viewClient, StateRuntime.realm, subject, StateRuntime.now())
        json(200, obj().put("subject", subject).put("sessionsEnded", e.sessionsEnded).put("preferencesDeleted", e.preferencesDeleted))
      catch
        case NonFatal(e) =>
          log.warn("erasure: {}", e.getClass.getSimpleName)
          refusal(503, "erasure_unavailable", "the erasure could not be completed; ask again", Vector("Retry-After" -> "2"))
  }
