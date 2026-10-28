package io.eitheror.casino.players.it

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.erasure.{ErasureFlow, ErasureWorkflow}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState}
import io.eitheror.casino.players.sessions.{GameSessionEntity, PlayerSessionEntity, SessionLimits, SessionRow, SessionStatus, SessionsView}
import com.thinkmorestupidless.ankka.core.EntityId

import java.time.Duration
import scala.concurrent.duration.*

/**
 * What `players` keeps outlives the process (T074): on its own kit, the service is stopped and started
 * again over the same database with a game session's timer set, an erasure in its hold and an export
 * under way, and each carries on from the database alone.
 */
class DurabilitySuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit =
    kit.stop()
    SharedKit.readopt()

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def game(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(GameSessionEntity.get).invoke()
  private def session(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerSessionEntity.get).invoke()
  private def op(method: String, path: String): Reply = kit.request(method, kit.operatorPath(path), Some("{}"), kit.asOperator())

  test("a restart over the same database: the timer fires, the hold resumes, the reconciler runs, the export completes, the session is listed, the workflows answer as before") {
    // a signed-in player with an open game session under a limit, so its timer is set
    val (email, id) = kit.activePlayer()
    val token       = kit.signIn(email).str("token")
    val ps          = kit.sessionOf(token)
    kit.publish(CasinoPlayers, id, RgLimitApplied(id, "SESSION_MINUTES", None, Some(30L), "player", kit.now()))
    kit.eventually("the limit")(Option.when(player(id).sessionLimitMinutes.contains(30))(()))
    val launched = kit.json("POST", kit.servicePath(s"/$id/game-sessions"),
      Json.obj("gameId" -> "g-7", "currency" -> "EUR", "demo" -> false, "playerSessionId" -> ps), kit.asService("game-provider"))
    assertEquals(launched.status, 201, launched.text)
    val gs = launched.str("sessionId")
    assert(kit.timerDueIn(SessionLimits.name(gs)).isDefined, "no timer before the restart")

    // a second player, closed, with an erasure filed and in its hold
    val (_, erased) = kit.activePlayer()
    assertEquals(op("POST", s"/players/$erased/close").status, 200)
    val filed = op("POST", s"/players/$erased/erasure")
    assertEquals(filed.status, 202, filed.text)
    val correlationId = filed.str("correlationId")

    // the first player's export, asked for just before the stop
    val asked = kit.request("POST", kit.playerPath(s"/$id/exports"), Some("{}"), kit.asToken(token))
    assertEquals(asked.status, 202, asked.text)
    val exportId = asked.str("exportId")

    kit.restart()

    // the session is still listed and the game session still open
    kit.eventually("the session listed")(SessionsView.ofPlayer(kit.views, id, SessionRow.Player, 0, 10).find(_.sessionId == ps))
    assertEquals(game(gs).endReason, None)

    // the export completes
    val path = kit.playerPath(s"/$id/exports/$exportId")
    val doc  = kit.eventually("the export", 30.seconds)(Option(kit.request("GET", path, None, kit.asToken(token))).filter(_.status == 200))
    assertEquals(doc.str("playerId"), id)

    // the timer is still there and still fires
    assert(kit.timerDueIn(SessionLimits.name(gs)).isDefined, "the timer did not outlive the restart")
    kit.advance(Duration.ofMinutes(30))
    kit.fireTimer(SessionLimits.name(gs))
    kit.eventually("ended limit", 10.seconds)(Option.when(game(gs).endReason.contains("limit"))(()))

    // the hold resumes and the erasure applies; the workflow answers its first outcome
    assertEquals(ErasureWorkflow.stateOf(kit.components, erased).correlationId, correlationId)
    kit.advance(Duration.ofDays(ErasureWorkflow.DefaultRetentionDays.toLong + 1))
    kit.eventually("erased", 30.seconds)(Option.when(ErasureWorkflow.stateOf(kit.components, erased).step == ErasureFlow.Erased)(()))
    assertEquals(op("GET", s"/players/$erased/erasure").str("correlationId"), correlationId)

    // the reconciler still runs: a session the realm dropped ends expired
    kit.realm.sessions.remove(kit.realmSessionOf(token))
    kit.eventually("expired", 10.seconds)(Option.when(session(ps).status == SessionStatus.Expired)(()))
  }
