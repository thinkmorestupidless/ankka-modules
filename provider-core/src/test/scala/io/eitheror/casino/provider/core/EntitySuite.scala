package io.eitheror.casino.provider.core

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

/**
 * What the house provider (spec 026) adds to the core's entities: a session in fun mode and in the
 * brand's language, a check the brand refused, and a stake whose round waits for its answer. The
 * simulator's own entity suite holds the rest.
 */
class EntitySuite extends munit.FunSuite with LogCapturing:

  private val launch =
    SessionEntity.Launch("goldvibe", "p1", "BRL", "test-slot", "default", "L1", 0L, 1000L, demo = true, language = "pt")

  private def stake(kit: EventSourcedTestKit[SessionEntity, Session, SessionEvent], id: String, round: String, awaiting: Boolean) =
    kit.call(SessionEntity.plan)(
      SessionEvent.CallbackPlanned(id, "k", Some(round), s"t-$id", None, RoundRole.Stake.toString, free = false, None, awaitingAnswer = awaiting)
    ): Unit

  test("a launch keeps whether it was in fun mode and the brand's language") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    assert(kit.currentState.demo)
    assertEquals(kit.currentState.language, "pt")
  }

  test("a launch that names neither is real money in no language, as the simulator's are") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(SessionEntity.Launch("goldvibe", "p1", "EUR", "book-of-ra", "default", "L1", 0L, 1000L)): Unit
    assert(!kit.currentState.demo)
    assertEquals(kit.currentState.language, "")
  }

  test("a check the brand refused leaves the session refused, once") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    assertEquals(kit.call(SessionEntity.refuseCheck)("session_expired").events.size, 1)
    assertEquals(kit.call(SessionEntity.refuseCheck)("session_expired").events, Vector.empty)
    assert(kit.currentState.refused)
    assert(!kit.currentState.checked)
  }

  test("a check refused on a session never launched is not found") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L9")
    assert(kit.call(SessionEntity.refuseCheck)("session_expired").isError)
  }

  test("a stake awaiting its answer leaves the round pending, and its acceptance opens it") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    stake(kit, "1", "r1", awaiting = true)
    assertEquals(kit.currentState.rounds("r1").state, Round.Pending)
    assertEquals(kit.currentState.openRounds, Vector.empty)
    kit.call(SessionEntity.answered)(SessionEvent.RoundAnswered("r1", accepted = true)): Unit
    assertEquals(kit.currentState.rounds("r1").state, Round.Open)
  }

  test("a stake refused after waiting leaves no round, as though it had never been opened") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    stake(kit, "1", "r1", awaiting = true)
    kit.call(SessionEntity.answered)(SessionEvent.RoundAnswered("r1", accepted = false)): Unit
    assertEquals(kit.currentState.round("r1"), None)
    assertEquals(kit.currentState.roundOrder, Vector.empty)
    assertEquals(kit.currentState.sent, Vector("t-1"))
  }

  test("an answer for a round that is not pending changes nothing") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    stake(kit, "1", "r1", awaiting = false)
    assertEquals(kit.call(SessionEntity.answered)(SessionEvent.RoundAnswered("r1", accepted = false)).events, Vector.empty)
    assertEquals(kit.currentState.rounds("r1").state, Round.Open)
  }

  private def scheduled(id: String) =
    val kit = EventSourcedTestKit.of(CallbackEntity, id)
    kit.call(CallbackEntity.schedule)(CallbackEvent.Scheduled("L1", "L1", "goldvibe", "credit", "/eitheror/credit", "{}", None, 1L)): Unit
    kit

  test("a callback planned for a retry shows when it is sent again, until it ends") {
    val kit = scheduled("c1")
    kit.call(CallbackEntity.retryAt)(5000L): Unit
    assertEquals(kit.currentState.nextRetryAt, Some(5000L))
    kit.call(CallbackEntity.end)(Callback.Acknowledged): Unit
    assertEquals(kit.currentState.nextRetryAt, None)
    assertEquals(kit.call(CallbackEntity.retryAt)(9000L).events, Vector.empty)
  }

  test("an operator abandons a callback that has not ended, with who, why and when") {
    val kit = scheduled("c1")
    kit.call(CallbackEntity.retryAt)(5000L): Unit
    val a = Abandonment("operator@goldvibe", "the brand wrote the round off by hand", 7000L)
    assertEquals(kit.call(CallbackEntity.abandon)(a).replyValue.outcome, Callback.Abandoned)
    assertEquals(kit.currentState.abandoned, Some(a))
    assertEquals(kit.currentState.nextRetryAt, None)
    assertEquals(kit.call(CallbackEntity.abandon)(a.copy(reason = "again")).events, Vector.empty)
  }

  test("an abandoned callback is not ended again by a late answer") {
    val kit = scheduled("c1")
    kit.call(CallbackEntity.abandon)(Abandonment("op", "gone", 1L)): Unit
    assertEquals(kit.call(CallbackEntity.end)(Callback.Acknowledged).events, Vector.empty)
    assertEquals(kit.currentState.outcome, Callback.Abandoned)
  }

  test("a callback that ended cannot be abandoned") {
    val kit = scheduled("c1")
    kit.call(CallbackEntity.end)(Callback.Exhausted): Unit
    assert(kit.call(CallbackEntity.abandon)(Abandonment("op", "late", 1L)).isError)
    assertEquals(kit.currentState.outcome, Callback.Exhausted)
  }

  // ---- the round's rules under the entity (spec 026 FR-011; review, blocking 2) ---------------------

  private def guarded(kit: EventSourcedTestKit[SessionEntity, Session, SessionEvent], id: String, round: String, role: RoundRole) =
    kit.call(SessionEntity.planGuarded)(
      SessionEvent.CallbackPlanned(id, "k", Some(round), s"t-$id", Option.when(role == RoundRole.Cancel)(s"c-$id"), role.toString, free = false, None)
    )

  private def realMoney(): EventSourcedTestKit[SessionEntity, Session, SessionEvent] =
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch.copy(demo = false, owner = "loteca-service")): Unit
    kit

  test("a launch keeps the game service that owned the code") {
    assertEquals(realMoney().currentState.owner, "loteca-service")
  }

  test("a second credit on a round is refused by the session, so two settles cannot both be sent") {
    val kit = realMoney()
    assert(!guarded(kit, "1", "r1", RoundRole.Stake).isError)
    assert(!guarded(kit, "2", "r1", RoundRole.Win).isError)
    val second = guarded(kit, "3", "r1", RoundRole.Loss)
    assert(second.isError)
    assertEquals(second.events, Vector.empty)
    assertEquals(kit.currentState.rounds("r1").credits, Vector("t-2"))
  }

  test("a stake on a round id already used is refused, and a credit on a pending round waits") {
    val kit = realMoney()
    assert(!guarded(kit, "1", "r1", RoundRole.Stake).isError)
    assert(guarded(kit, "2", "r1", RoundRole.Stake).isError)
    stake(kit, "3", "r2", awaiting = true)
    assertEquals(kit.currentState.refusal(RoundRole.Win, Some("r2")), Some(Session.Refusal.Pending))
    assert(guarded(kit, "4", "r2", RoundRole.Win).isError)
  }

  test("a cancel needs a debit on a round not yet ended, and is refused the second time") {
    val kit = realMoney()
    assertEquals(kit.currentState.refusal(RoundRole.Cancel, Some("r1")), Some(Session.Refusal.NoOpenDebit))
    assert(!guarded(kit, "1", "r1", RoundRole.Stake).isError)
    assert(!guarded(kit, "2", "r1", RoundRole.Cancel).isError)
    assert(guarded(kit, "3", "r1", RoundRole.Cancel).isError)
  }

  test("the simulator's unguarded plan still sends what a broken provider would") {
    val kit = realMoney()
    stake(kit, "1", "r1", awaiting = false)
    kit.call(SessionEntity.plan)(SessionEvent.CallbackPlanned("2", "k", Some("r1"), "t-2", None, RoundRole.Win.toString, free = false, None)): Unit
    assert(!kit.call(SessionEntity.plan)(SessionEvent.CallbackPlanned("3", "k", Some("r1"), "t-3", None, RoundRole.Win.toString, free = false, None)).isError)
  }
