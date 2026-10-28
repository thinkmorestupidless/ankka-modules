package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.it.SharedKit
import io.eitheror.casino.players.player.{PlayerEntity, SessionId}

import java.util.UUID
import scala.concurrent.duration.*

/** `SessionEndWorkflow` on the integration kit (research R6): games, then the session, then the player. */
class SessionEndWorkflowSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def flow(id: String) = kit.components.forWorkflow(EntityId(id)).call(SessionEndWorkflow.status).invoke()
  private def sessionState(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerSessionEntity.get).invoke()
  private def game(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(GameSessionEntity.get).invoke()

  /** A signed-in player's session, with `games` game sessions opened under it. */
  private def withGames(games: Int): (String, String, Vector[String]) =
    val (email, playerId) = kit.activePlayer()
    val token   = kit.signIn(email).str("token")
    val session = kit.sessionOf(token)
    val ids = (1 to games).toVector.map { i =>
      val g = s"gs-${UUID.randomUUID().toString.take(8)}"
      kit.components.forEventSourcedEntity(EntityId(session)).call(PlayerSessionEntity.addGameSession).invoke(AddGameSession(g)): Unit
      kit.components.forEventSourcedEntity(EntityId(g)).call(GameSessionEntity.open)
        .invoke(OpenGameSession(playerId, session, s"game-$i", "EUR", false, kit.now())): Unit
      kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.gameSessionOpened).invoke(SessionId(g, kit.now())): Unit
      g
    }
    (playerId, session, ids)

  test("games end with the reason, then the session takes its status, then the player is told") {
    val (playerId, session, games) = withGames(2)
    assert(SessionEndWorkflow.end(kit.components, session, EndReason.Forced, Some(5.seconds)))
    val f = flow(session)
    assertEquals((f.done, f.gamesEnded, f.playerId), (true, games.sorted, playerId))
    games.foreach(g => assertEquals((game(g).status, game(g).endReason), (GameSessionState.Ended, Some(EndReason.Forced))))
    assertEquals(sessionState(session).status, SessionStatus.Forced)
    val p = kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
    assert(!p.openPlayerSessions(session))
    assert(p.todaysGameSessions.forall(_.endedAt.isDefined))
  }

  test("a session with no game sessions passes through games; a sign-out completes it") {
    val (_, session, _) = withGames(0)
    assert(SessionEndWorkflow.end(kit.components, session, EndReason.Logout, Some(5.seconds)))
    assertEquals((flow(session).gamesEnded, sessionState(session).status), (Vector.empty, SessionStatus.Completed))
  }

  test("a second start under the same id, with another reason, joins the first and answers its outcome") {
    val (_, session, _) = withGames(1)
    assert(SessionEndWorkflow.end(kit.components, session, EndReason.Expired, Some(5.seconds)))
    assert(SessionEndWorkflow.end(kit.components, session, EndReason.Superseded, Some(5.seconds)))
    assertEquals((flow(session).reason, sessionState(session).status), (Some(EndReason.Expired), SessionStatus.Expired))
  }

  test("an unknown reason is refused") {
    val ex = intercept[Exception](kit.components.forWorkflow(EntityId(s"ps-${UUID.randomUUID()}")).call(SessionEndWorkflow.start)
      .invoke(StartEnd("bored", kit.now())))
    assert(ex.getMessage.contains("no such reason"), ex.getMessage)
  }
