package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal as Marked
import io.eitheror.casino.reporting.{Recorded, Settings, TestKeyring}
import io.eitheror.casino.reporting.Facts.*

import java.nio.charset.StandardCharsets.UTF_8

/** T023: `Session` for a login and a game session, the network address and user agent as `Personal`; `sessions-facts`. */
class SessionSuite extends munit.FunSuite:
  private def kit(id: String) = KeyValueEntityTestKit.of(Session, Session.id(1, id))

  private val login = f.PlayerSessionStarted("p-1", "s-1", Marked("203.0.113.9"), Marked("Firefox/140"), at)

  test("a login session: started, its address and agent under player/p-1, ended with its reason") {
    TestKeyring.around { _ =>
      val k = kit("s-1")
      assert(k.call(Session.record)(SessionFact(login)).replyValue)
      val s = k.currentState
      assertEquals((s.kind, s.player, s.startedAt, s.open), ("player", "p-1", Some(at), true))
      assertEquals((Personals.reveal(s.networkAddress), Personals.reveal(s.userAgent)), (Some("203.0.113.9"), Some("Firefox/140")))
      assertEquals(s.networkAddress.map(_.subject.value), Some("player/p-1"))
      val stored = String(Session.stateSerializer.toBytes(s), UTF_8)
      assert(!stored.contains("203.0.113.9") && !stored.contains("Firefox"), stored)
      k.call(Session.record)(SessionFact(f.PlayerSessionEnded("p-1", "s-1", "logout", at.plusSeconds(600)))): Unit
      assertEquals((k.currentState.endedAt, k.currentState.closeReason, k.currentState.open), (Some(at.plusSeconds(600)), Some("logout"), false))
    }
  }

  test("a game session: its game and login session; ended with its duration; a repeat changes nothing") {
    val k = kit("gs-1")
    val started = f.GameSessionStarted("p-1", "gs-1", "g-1", "s-1", at)
    k.call(Session.record)(SessionFact(started)): Unit
    assert(!k.call(Session.record)(SessionFact(started)).replyValue)
    k.call(Session.record)(SessionFact(f.GameSessionEnded("p-1", "gs-1", "g-1", 12, at.plusSeconds(720), Some("player")))): Unit
    val s = k.currentState
    assertEquals((s.kind, s.gameId, s.playerSessionId, s.durationMinutes, s.closeReason, s.open), ("game", Some("g-1"), Some("s-1"), Some(12), Some("player"), false))
  }

  test("an end before its start still leaves both") {
    val k = kit("gs-2")
    k.call(Session.record)(SessionFact(f.GameSessionEnded("p-1", "gs-2", "g-1", 1, at.plusSeconds(60)))): Unit
    k.call(Session.record)(SessionFact(f.GameSessionStarted("p-1", "gs-2", "g-1", "s-1", at))): Unit
    assertEquals((k.currentState.startedAt, k.currentState.endedAt), (Some(at), Some(at.plusSeconds(60))))
  }

  test("sessions-facts sends each fact to its generation's session") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(SessionsFacts(Settings("goldvibe")), client = r.client)
    assertEquals(kit.onMessage(Some(f.GameSessionStarted("p-1", "gs-1", "g-1", "s-1", at))).effect, ConsumerEffect.Done)
    assertEquals(r.calls.map(c => (c.component, c.entity, c.method)), Vector(("session", "g1/gs-1", "record")))
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
  }
