package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, TestTransport, WorkflowTestKit}
import io.eitheror.casino.backoffice.StateRuntime

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** A realm that counts its refreshes and answers what the suite says (data-model.md `Session`). */
final class CountingRealm extends RealmClient:
  val refreshes = AtomicInteger()
  @volatile var revoked: Vector[Secret]                                            = Vector.empty
  @volatile var onRefresh: (Secret, Instant) => Either[RealmRefusal, RealmTokens] = (_, at) => Right(SessionSuite.tokens(refreshes.get, at))

  def exchange(code: String, verifier: String, redirectUri: String, now: Instant): Either[RealmRefusal, RealmTokens] =
    Right(SessionSuite.tokens(0, now))
  def refresh(refreshToken: Secret, now: Instant): Either[RealmRefusal, RealmTokens] =
    refreshes.incrementAndGet(): Unit
    onRefresh(refreshToken, now)
  def revoke(refreshToken: Secret): Either[RealmRefusal, Unit] =
    revoked = revoked :+ refreshToken
    Right(())

object SessionSuite:
  val t0: Instant = Instant.parse("2026-11-15T10:00:00Z")

  /** The realm's answer `n`: an access token for five minutes, the session for thirty. */
  def tokens(n: Int, at: Instant, roles: Set[String] = Set("brand_goldvibe", "brand_support")): RealmTokens =
    RealmTokens(Secret(s"access-token-$n"), Secret(s"refresh-token-$n"), at.plusSeconds(300), at.plusSeconds(1800), "8c1f-sub",
      "Ada Operator", "ada@example.com", roles)

class SessionSuite extends munit.FunSuite:
  import SessionSuite.*

  /** One session and its refresh flows, wired through stubs as the runtime wires them. */
  final class Wired(id: String = "s-1"):
    val realm   = CountingRealm()
    val session = EventSourcedTestKit.of(Session, id)
    private val toSession = TestTransport()
      .stub(Session.refreshTokenFor)(g => session.call(Session.refreshTokenFor)(g).replyValue)
      .stub(Session.refreshed)(r => session.call(Session.refreshed)(r).replyValue)
      .stub(Session.end)(e => session.call(Session.end)(e).replyValue)
    private var flows = Map.empty[Int, WorkflowTestKit[SessionRefresh, RefreshFlow]]
    private var last  = 0

    def flow(generation: Int): WorkflowTestKit[SessionRefresh, RefreshFlow] =
      flows.getOrElse(generation, {
        val kit = WorkflowTestKit.of(SessionRefresh, SessionRefresh.id(id, generation).value, toSession.client)
        flows = flows.updated(generation, kit)
        kit
      })

    /** What `Tokens` calls: the session's queries and the refresh flow of the generation it names. */
    val client = TestTransport()
      .stub(Session.token)(a => session.call(Session.token)(a).replyValue)
      .stub(Session.current)(a => session.call(Session.current)(a).replyValue)
      .stub(SessionRefresh.start) { s => last = s.generation; flow(s.generation).call(SessionRefresh.start)(s).replyValue }
      .stub(SessionRefresh.status)(() => flow(last).state)
      .client

    @volatile var now: Instant = t0
    def tokensFlow             = Tokens(client, () => now, wait = 2.seconds, poll = 1.milli)

    def open(): Unit = session.call(Session.open)(Open(tokens(0, t0), t0)).replyValue: Unit

  override def beforeEach(context: BeforeEach): Unit = StateRuntime.realm = CountingRealm()

  test("a token with 30 seconds or more left is answered as it is, with no refresh") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.open()
    w.now = t0.plusSeconds(270)
    w.tokensFlow.token("s-1") match
      case TokenResult.Answered(token, expiresAt, roles, rolesChanged) =>
        assertEquals(token.reveal, "access-token-0")
        assertEquals(expiresAt, t0.plusSeconds(300))
        assertEquals(roles, Set("brand_goldvibe", "brand_support"))
        assert(!rolesChanged)
      case other => fail(s"answered $other")
    assertEquals(w.realm.refreshes.get, 0)
  }

  test("under 30 seconds left the token is refreshed, Refreshed recorded, and the new one answered") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.open()
    w.now = t0.plusSeconds(271)
    w.tokensFlow.token("s-1") match
      case TokenResult.Answered(token, _, _, _) => assertEquals(token.reveal, "access-token-1")
      case other                               => fail(s"answered $other")
    assertEquals(w.realm.refreshes.get, 1)
    assert(w.session.allEvents.exists(_.isInstanceOf[SessionEvent.Refreshed]))
    assertEquals(w.session.currentState.refreshes, 1)
    assertEquals(w.session.currentState.refreshToken.map(_.reveal), Some("refresh-token-1"))
  }

  test("two concurrent token calls near expiry make one refresh") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.open()
    val near = TokenAsk(t0.plusSeconds(290))
    // Both callers read the session before either refreshes: both are told it is stale, of one generation.
    val a = w.session.call(Session.token)(near).replyValue
    val b = w.session.call(Session.token)(near).replyValue
    assertEquals(a.state, TokenAnswer.Stale)
    assertEquals(b.state, TokenAnswer.Stale)
    assertEquals(a.generation, b.generation)
    // Both start the generation's one refresh: the first claims it, the second joins it.
    val first  = w.flow(a.generation).call(SessionRefresh.start)(StartRefresh("s-1", a.generation))
    val second = w.flow(b.generation).call(SessionRefresh.start)(StartRefresh("s-1", b.generation))
    assert(first.replyValue)
    assert(!second.replyValue)
    assertEquals(w.realm.refreshes.get, 1)
    assertEquals(w.flow(a.generation).state.status, RefreshFlow.Done)
    // Each then reads the one new token.
    val after = w.session.call(Session.current)(near).replyValue
    assertEquals(after.accessToken.map(_.reveal), Some("access-token-1"))
    // A refresh recorded for a generation already moved on is not recorded again.
    val late = w.session.call(Session.refreshed)(Refresh(a.generation, tokens(9, near.now), near.now))
    assert(!late.replyValue.applied)
    assert(late.events.isEmpty)
  }

  test("two token calls in turn near expiry make one refresh: the second reads the first's token") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.open()
    w.now = t0.plusSeconds(290)
    val first  = w.tokensFlow.token("s-1")
    val second = w.tokensFlow.token("s-1")
    assertEquals(first, second)
    assertEquals(w.realm.refreshes.get, 1)
  }

  test("a refresh the realm refuses ends the session revoked, and every later token is gone") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.realm.onRefresh = (_, _) => Left(RealmRefusal.Refused("invalid_grant"))
    w.open()
    w.now = t0.plusSeconds(290)
    assertEquals(w.tokensFlow.token("s-1"), TokenResult.Ended)
    assertEquals(w.session.currentState.endReason, Some(EndReason.Revoked))
    w.now = t0.plusSeconds(291)
    assertEquals(w.tokensFlow.token("s-1"), TokenResult.Ended)
    assertEquals(w.realm.refreshes.get, 1)
  }

  test("a realm that does not answer leaves the session open and the caller told it is unavailable") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.realm.onRefresh = (_, _) => Left(RealmRefusal.Unavailable("connect timed out"))
    w.open()
    w.now = t0.plusSeconds(290)
    assert(w.tokensFlow.token("s-1").isInstanceOf[TokenResult.Unavailable])
    assert(w.session.currentState.open)
  }

  test("roles changed by a refresh are answered rolesChanged once, to the call that refreshed") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.realm.onRefresh = (_, at) => Right(tokens(1, at, Set("brand_goldvibe", "brand_admin")))
    w.open()
    w.now = t0.plusSeconds(290)
    w.tokensFlow.token("s-1") match
      case TokenResult.Answered(_, _, roles, changed) =>
        assertEquals(roles, Set("brand_goldvibe", "brand_admin"))
        assert(changed)
      case other => fail(s"answered $other")
    w.tokensFlow.token("s-1") match
      case TokenResult.Answered(_, _, _, changed) => assert(!changed)
      case other                                 => fail(s"answered $other")
  }

  test("an ended session's tokens are cleared from its state by Ended, and it is deleted after 24 hours") {
    val w = Wired()
    w.open()
    val ended = w.session.call(Session.end)(End(EndReason.SignedOut, t0.plusSeconds(60)))
    assert(ended.replyValue.ended)
    assertEquals(ended.replyValue.refreshToken.map(_.reveal), Some("refresh-token-0"))
    val s = w.session.currentState
    assertEquals(s.accessToken, None)
    assertEquals(s.refreshToken, None)
    assertEquals(s.endReason, Some(EndReason.SignedOut))
    assertEquals(ended.retention, Some(Retention.ExpireAfter(24.hours)))
    assertEquals(w.session.call(Session.token)(TokenAsk(t0.plusSeconds(61))).replyValue.state, TokenAnswer.Ended)
    // Ending again is no event and hands back no token.
    val again = w.session.call(Session.end)(End(EndReason.SignedOut, t0.plusSeconds(62)))
    assert(!again.replyValue.ended)
    assert(again.events.isEmpty)
    assertEquals(again.replyValue.refreshToken, None)
  }

  test("a session never opened answers its token as ended") {
    val w = Wired("never")
    assertEquals(w.session.call(Session.token)(TokenAsk(t0)).replyValue.state, TokenAnswer.Ended)
  }

  test("a session is opened once") {
    val w = Wired()
    w.open()
    assert(w.session.call(Session.open)(Open(tokens(5, t0), t0)).isError)
  }

  test("the expiry timer ends the session at the refresh token's expiry, and not before") {
    val w = Wired()
    w.open()
    val early = w.session.call(Session.expire)(ExpireAsk(t0.plusSeconds(1000)))
    assert(!early.replyValue.ended)
    assertEquals(early.replyValue.due, Some(t0.plusSeconds(1800)))
    assert(early.events.isEmpty)
    val due = w.session.call(Session.expire)(ExpireAsk(t0.plusSeconds(1800)))
    assert(due.replyValue.ended)
    assertEquals(w.session.currentState.endReason, Some(EndReason.Expired))
    assertEquals(w.session.call(Session.token)(TokenAsk(t0.plusSeconds(1801))).replyValue.state, TokenAnswer.Ended)
  }

  test("a token is never asked of the realm once the refresh token has expired") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.open()
    w.now = t0.plusSeconds(1800)
    assertEquals(w.tokensFlow.token("s-1"), TokenResult.Ended)
    assertEquals(w.realm.refreshes.get, 0)
  }

  test("no event, state, command or answer prints a token, a name or an email") {
    val w = Wired()
    StateRuntime.realm = w.realm
    w.open()
    w.now = t0.plusSeconds(290)
    val answered = w.tokensFlow.token("s-1")
    val printed = (w.session.allEvents.map(_.toString) ++ Vector(w.session.currentState.toString, answered.toString,
      Open(tokens(0, t0), t0).toString, tokens(0, t0).toString, Refresh(0, tokens(0, t0), t0).toString,
      w.session.call(Session.refreshTokenFor)(1).replyValue.toString, w.session.call(Session.current)(TokenAsk(w.now)).replyValue.toString,
      w.session.call(Session.end)(End(EndReason.SignedOut, w.now)).replyValue.toString)).mkString("\n")
    Seq("access-token-0", "refresh-token-0", "access-token-1", "refresh-token-1", "Ada Operator", "ada@example.com").foreach { secret =>
      assert(!printed.contains(secret), s"'$secret' printed in:\n$printed")
    }
  }
