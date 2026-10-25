package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{CasinoSessions, PlayerSessionEnded}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.player.{PlayerEntity, Suspend}
import io.eitheror.casino.players.sessions.{PlayerSessionEntity, SessionStatus}

import java.time.Instant
import scala.concurrent.duration.*

/** The realm's listener, its authenticators and the reconciler (FR-008, FR-009, FR-013, SC-002, SC-004). */
class RealmSuite extends munit.FunSuite:
  override val munitTimeout = 240.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val webhookSecret = "x-webhook-secret" -> "kit-webhook-secret"
  private val realmSecret   = "x-realm-secret" -> "kit-realm-secret"

  private def status(id: String) = kit.components.forEventSourcedEntity(EntityId(id)).call(PlayerSessionEntity.get).invoke().status

  /** A signed-in player: the address, the player, the realm session and the player session. */
  private def signedIn(): (String, String, String, String) =
    val (email, id) = kit.activePlayer()
    val token = kit.signIn(email).str("token")
    (email, id, kit.realmSessionOf(token), kit.sessionOf(token))

  private def webhook(kind: String, realmSession: String, realm: String = kit.settings.realm, secret: (String, String) = webhookSecret): Reply =
    kit.json("POST", kit.realmPath("/webhook"), Json.obj("type" -> kind, "realmId" -> realm, "sessionId" -> realmSession, "userId" -> "u",
      "timestamp" -> System.currentTimeMillis), kit.asVisitor :+ secret)

  private def maySignIn(email: String): Reply =
    kit.json("POST", kit.realmPath("/may-sign-in"), Json.obj("email" -> email), kit.asVisitor :+ realmSecret)

  test("the webhook: LOGOUT completes the session, SESSION_EXPIRED expires one, LOGIN changes nothing; always 200 {ok}") {
    val (_, _, realmA, a) = signedIn()
    val (_, _, realmB, b) = signedIn()
    val (_, _, realmC, c) = signedIn()
    assertEquals(webhook("LOGOUT", realmA).json.get("ok").asBoolean, true)
    kit.eventually("completed")(Option.when(status(a) == SessionStatus.Completed)(()))
    assertEquals(webhook("SESSION_EXPIRED", realmB).status, 200)
    kit.eventually("expired")(Option.when(status(b) == SessionStatus.Expired)(()))
    assertEquals(webhook("LOGIN", realmC).status, 200)
    assertEquals(webhook("LOGOUT", realmC, realm = "casino-other").status, 200)
    assertEquals(kit.json("POST", kit.realmPath("/webhook"), Json.obj("type" -> "LOGOUT", "realmId" -> kit.settings.realm),
      kit.asVisitor :+ webhookSecret).status, 200)
    Thread.sleep(500)
    assertEquals(status(c), SessionStatus.Active)
  }

  test("the webhook with the wrong secret is 401") {
    val r = webhook("LOGOUT", "rs-1", secret = "x-webhook-secret" -> "wrong")
    assertEquals((r.status, r.str("error")), (401, "unauthorized"))
  }

  test("may-sign-in: allowed for an active player; suspended after a suspension; cooling off with its end; refused when RG is silent") {
    val (email, id) = kit.activePlayer()
    assertEquals(maySignIn(email).json.get("allowed").asBoolean, true)
    val until = Instant.parse("2031-01-01T00:00:00Z")
    kit.rg.coolingOff(id, until)
    val cooling = maySignIn(email)
    assertEquals((cooling.json.get("allowed").asBoolean, cooling.str("reason"), cooling.str("until")), (false, "cooling_off", until.toString))
    kit.rg.logIn.clear()
    kit.rg.silent = true
    val started = System.nanoTime()
    val silent  = maySignIn(email)
    val took    = (System.nanoTime() - started) / 1_000_000
    kit.rg.silent = false
    assertEquals(silent.json.get("allowed").asBoolean, false)
    assert(took < kit.settings.realmAnswerBound.toMillis + 500, s"took $took ms")
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.suspend).invoke(Suspend("kit", None, kit.now())): Unit
    val suspended = maySignIn(email)
    assertEquals((suspended.json.get("allowed").asBoolean, suspended.str("reason")), (false, "suspended"))
    assertEquals(kit.json("POST", kit.realmPath("/may-sign-in"), Json.obj("email" -> email), kit.asVisitor).status, 401)
  }

  test("may-sign-in at the assumed peak: 1,000 asks at 20 a second, RG answering in 20 ms, p99 under 100 ms") {
    val (email, _) = kit.activePlayer()
    kit.rg.delayMillis = 20
    val times = (1 to 1000).map { i =>
      val due = System.nanoTime() + 50_000_000L
      val started = System.nanoTime()
      val r = maySignIn(email)
      assertEquals(r.status, 200)
      val took = (System.nanoTime() - started) / 1_000_000.0
      val left = (due - System.nanoTime()) / 1_000_000L
      if left > 0 then Thread.sleep(left)
      took
    }.sorted
    kit.rg.delayMillis = 0
    val p99 = times((times.size * 99) / 100 - 1)
    println(f"may-sign-in: p50 ${times(times.size / 2)}%.1f ms, p99 $p99%.1f ms over ${times.size} asks")
    assert(p99 < 100.0, f"p99 $p99%.1f ms")
  }

  test("gamstop-refusal: 200 and passed to responsible gambling") {
    val r = kit.json("POST", kit.realmPath("/gamstop-refusal"), Json.obj("email" -> "g@example.test", "result" -> "excluded"),
      kit.asVisitor :+ realmSecret)
    assertEquals(r.status, 200, r.text)
    assertEquals(kit.rg.asked("gamstop-refusal").size, 1)
  }

  test("the reconciler: a session the realm no longer holds ends expired within two intervals; a silent realm for a tick does not stop it") {
    val (_, _, realmA, a) = signedIn()
    kit.realm.sessions.remove(realmA)
    kit.eventually("expired", 3.seconds)(Option.when(status(a) == SessionStatus.Expired)(()))
    val f = kit.eventually("player_session_ended")(kit.facts(CasinoSessions).collectFirst { case e: PlayerSessionEnded if e.sessionId == a => e })
    assertEquals(f.closeReason, "expired")

    val (_, _, realmB, b) = signedIn()
    kit.realm.silent = true
    Thread.sleep(1500)
    kit.realm.silent = false
    kit.realm.sessions.remove(realmB)
    kit.eventually("expired after the silent tick", 60.seconds)(Option.when(status(b) == SessionStatus.Expired)(()))
  }
