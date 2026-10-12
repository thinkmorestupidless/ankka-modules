package io.eitheror.casino.simulator.st8

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.simulator.Simulator
import io.eitheror.casino.simulator.api.Access
import io.eitheror.casino.simulator.core.*

/**
 * The game page (FR-008; `main.go:760-807`): loading it sends `check` with the launch ticket and,
 * on `ok`, keeps the session ticket the brand answered and serves the page; on anything else it
 * serves the session-expired page, with HTTP 200, as the stand-in does. A session on a scripted
 * scenario starts walking it here (research R4).
 */
final class GamePage(sim: Simulator) extends HttpEndpoint("/game"):

  val acl: Acl = Access.public

  private lazy val page    = Http.resource("/pages/game.html")
  private lazy val expired = Http.resource("/pages/session-expired.html")

  get("/") { () =>
    query.raw("token").filter(_.nonEmpty) match
      case None => Http.error(400, "missing token")
      case Some(launchToken) =>
        sim.play.session(launchToken) match
          case None => Http.html(expired)
          case Some(session) =>
            val call = Call("check", Some(launchToken), None, None, None, None, None, None, None, None, None)
            val sent = sim.play.send(session, call, "stub", "current", Wiring.brands.answerWait(session.brand), None)
            sim.play.end(sent.callbackId, sent)
            sent.reading.filter(r => sent.attempt.httpStatus.contains(200) && Wiring.protocol.accepted(r)) match
              case None => Http.html(expired)
              case Some(reading) =>
                val walletToken = reading.token.getOrElse("")
                sim.client.forEventSourcedEntity(EntityId(launchToken)).call(SessionEntity.check).invoke(walletToken)
                if session.scenario != "default" then
                  sim.client.forWorkflow(EntityId(launchToken)).call(ScenarioPlay.start).invoke(ScenarioPlay.Start(launchToken, session.scenario))
                Http.html(page.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(walletToken)).replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(launchToken)))
  }

/** The key document (FR-011; contracts/st8-wire.md): what a brand on a test installation names as its provider key. */
final class KeyDocument(sim: Simulator) extends HttpEndpoint("/.well-known"):
  val acl: Acl = Access.public
  get("/st8-keys")(() => Http.json(200, sim.keys.document()))

/** The stand-in's health (`main.go:717-727`): ready once the key is held and registered where configured. */
final class Health(sim: Simulator) extends HttpEndpoint("/health"):
  val acl: Acl = Access.public
  get("/") { () =>
    if sim.ready.get() then Http.raw(200, "application/json", """{"status":"ready"}""")
    else Http.raw(503, "application/json", """{"status":"starting"}""")
  }
