package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import java.time.Instant

/** data-model.md's two session entities (T012). */
class SessionEntitiesSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-25T10:00:00Z")

  private def opened =
    val kit = EventSourcedTestKit.of(PlayerSessionEntity, "ps-1")
    kit.call(PlayerSessionEntity.open)(OpenPlayerSession("p-1", "realm-s-1", "192.0.2.44", "Mozilla/5.0", "fp-1", at)): Unit
    kit

  test("a player session's Open records the player, the realm session, the three personal fields and active") {
    val s = opened.currentState
    assertEquals((s.playerId, s.realmSessionId, s.status, s.startedAt), ("p-1", "realm-s-1", SessionStatus.Active, Some(at)))
    assertEquals((s.networkAddress.reveal, s.userAgent.reveal, s.deviceFingerprint.reveal), ("192.0.2.44", "Mozilla/5.0", "fp-1"))
    assert(!opened.call(PlayerSessionEntity.open)(OpenPlayerSession("p-2", "x", "", "", "", at)).persisted)
  }

  test("AddGameSession records a game session once, and none on an ended session") {
    val kit = opened
    assert(kit.call(PlayerSessionEntity.addGameSession)(AddGameSession("gs-1")).replyValue)
    assert(!kit.call(PlayerSessionEntity.addGameSession)(AddGameSession("gs-1")).persisted)
    assertEquals(kit.currentState.gameSessions, Vector("gs-1"))
    kit.call(PlayerSessionEntity.end)(EndSession(SessionStatus.Completed, at)): Unit
    assert(!kit.call(PlayerSessionEntity.addGameSession)(AddGameSession("gs-2")).replyValue)
  }

  test("End sets the reason as the status and the end time, answers the game sessions, and again is no event") {
    SessionStatus.endings.foreach { reason =>
      val kit = opened
      kit.call(PlayerSessionEntity.addGameSession)(AddGameSession("gs-1")): Unit
      val r = kit.call(PlayerSessionEntity.end)(EndSession(reason, at.plusSeconds(60)))
      assertEquals(r.replyValue, SessionEnd(true, Vector("gs-1")), reason)
      assertEquals((kit.currentState.status, kit.currentState.endedAt), (reason, Some(at.plusSeconds(60))), reason)
      val again = kit.call(PlayerSessionEntity.end)(EndSession(SessionStatus.Forced, at.plusSeconds(120)))
      assert(!again.persisted && !again.replyValue.ended, reason)
      assertEquals(kit.currentState.status, reason)
    }
    assertEquals(opened.call(PlayerSessionEntity.end)(EndSession("bored", at)).replyValue.refusal, Some("invalid_reason"))
  }

  test("a game session's Open and End with durationMinutes the whole minutes between, and again no event") {
    val kit = EventSourcedTestKit.of(GameSessionEntity, "gs-1")
    kit.call(GameSessionEntity.open)(OpenGameSession("p-1", "ps-1", "g-1", "EUR", demo = false, at)): Unit
    assertEquals(kit.currentState.status, GameSessionState.Active)
    val r = kit.call(GameSessionEntity.end)(EndGameSession("limit", at.plusSeconds(17 * 60 + 59)))
    assert(r.replyValue.ended)
    assertEquals((kit.currentState.durationMinutes, kit.currentState.endReason), (Some(17L), Some("limit")))
    assert(!kit.call(GameSessionEntity.end)(EndGameSession("player", at.plusSeconds(3600))).persisted)
    assertEquals(kit.currentState.endReason, Some("limit"))
  }

  test("each end reason of data-model.md is accepted; another is refused") {
    GameEndReason.all.foreach { reason =>
      val kit = EventSourcedTestKit.of(GameSessionEntity, s"gs-$reason")
      kit.call(GameSessionEntity.open)(OpenGameSession("p-1", "ps-1", "g-1", "EUR", demo = true, at)): Unit
      assert(kit.call(GameSessionEntity.end)(EndGameSession(reason, at)).replyValue.ended, reason)
    }
    assertEquals(Set("player", "limit", "forced", "logout", "expired", "suspended", "superseded"), GameEndReason.all)
    val unknown = EventSourcedTestKit.of(GameSessionEntity, "gs-x")
    assertEquals(unknown.call(GameSessionEntity.end)(EndGameSession("player", at)).replyValue.ended, false, "an unknown session is the route's session_not_found")
  }
