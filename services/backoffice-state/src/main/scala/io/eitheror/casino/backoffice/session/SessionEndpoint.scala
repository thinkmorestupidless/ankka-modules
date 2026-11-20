package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import io.eitheror.casino.backoffice.{Settings, StateRuntime}
import io.eitheror.casino.backoffice.Answers.*
import io.eitheror.casino.contract.access.Routes
import org.slf4j.LoggerFactory

/**
 * `/sessions` (contracts/routes.md), served to the brand's `backoffice` alone. The access token leaves
 * only in the token route's answer; nothing here logs a body, a token or a code, and a refusal names
 * the realm's error code, never what was sent.
 */
final class SessionEndpoint(settings: Settings, clients: EndpointClients) extends HttpEndpoint("/sessions"):
  val acl: Acl = Routes.serviceOf("backoffice")

  private val log        = LoggerFactory.getLogger("backoffice-state.sessions")
  private val components = clients.componentClient
  private val tokens     = Tokens(components, () => StateRuntime.now())

  /** RFC 7636's verifier: 43 to 128 unreserved characters. */
  private val Verifier = "[A-Za-z0-9._~-]{43,128}".r

  postBody("") { (text: String) => open(text) }
  post("/{id}/token") { (id: String) => token(id) }
  delete("/{id}") { (id: String) => end(id) }

  /** `{code, verifier, redirectUri}`: the code exchanged at the realm with PKCE; `{session, subject, expiresAt}`. */
  private def open(text: String): Reply =
    val body = parse(text)
    def field(name: String) = body.flatMap(b => Option(b.get(name))).filter(_.isTextual).map(_.asText).filter(_.nonEmpty)
    (field("code"), field("verifier"), field("redirectUri")) match
      case (Some(code), Some(verifier), Some(redirect)) =>
        if !Verifier.matches(verifier) then refusal(400, "bad_verifier", "the PKCE verifier is 43 to 128 unreserved characters")
        else if redirect != settings.callback.toString then
          refusal(400, "redirect_uri_mismatch", s"the redirect is the backoffice's callback, ${settings.callback}")
        else
          val now = StateRuntime.now()
          StateRuntime.realm.exchange(code, verifier, redirect, now) match
            case Left(RealmRefusal.Refused(error)) => refusal(400, "code_refused", s"the realm refused the code: $error")
            case Left(RealmRefusal.Unavailable(why)) =>
              log.warn("sign-in: the realm did not answer the code exchange: {}", why)
              refusal(503, "realm_unavailable", "the realm did not answer", Vector("Retry-After" -> "2"))
            case Right(t) =>
              val id = Session.newId()
              components.forEventSourcedEntity(id).call(Session.open).invoke(Open(t, now)): Unit
              SessionExpiry.schedule(StateRuntime.timers(), id.toString, t.refreshExpiresAt, now)
              json(200, obj().put("session", id.toString).put("subject", t.subject).put("expiresAt", t.refreshExpiresAt.toString))
      case _ => refusal(400, "bad_request", "the body is {code, verifier, redirectUri}")

  /** `{accessToken, expiresAt, roles, rolesChanged}`, refreshed when under 30 seconds remain; `404` once the session ended. */
  private def token(id: String): Reply =
    if !Session.validId(id) then ended
    else
      tokens.token(id) match
        case TokenResult.Answered(access, expiresAt, roles, rolesChanged) =>
          val o = obj().put("accessToken", access.reveal).put("expiresAt", expiresAt.toString).put("rolesChanged", rolesChanged)
          val r = o.putArray("roles")
          roles.toVector.sorted.foreach(r.add)
          json(200, o, Vector("Cache-Control" -> "no-store"))
        case TokenResult.Ended => ended
        case TokenResult.Unavailable(why) =>
          log.warn("token: {}", why)
          refusal(503, "realm_unavailable", why, Vector("Retry-After" -> "2"))

  /** Ends the session and revokes its refresh token at the realm; `204` whether or not it was open. */
  private def end(id: String): Reply =
    if Session.validId(id) then
      val o = components.forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(id)).call(Session.end)
        .invoke(End(EndReason.SignedOut, StateRuntime.now()))
      if o.ended then StateRuntime.timers().delete(SessionExpiry.name(id))
      o.refreshToken.foreach { t =>
        StateRuntime.realm.revoke(t).left.foreach(why => log.warn("sign-out: the realm's revocation failed: {}", why))
      }
    noContent

  private def ended: Reply = refusal(404, "session_ended", "no such session, or it has ended")
