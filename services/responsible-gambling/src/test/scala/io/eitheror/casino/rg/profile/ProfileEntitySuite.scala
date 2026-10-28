package io.eitheror.casino.rg.profile

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.rg.profile.ProfileEvent.*

import java.time.{Duration, Instant}

/** data-model.md's command table for `ProfileEntity`, row by row and in its order (T008). */
class ProfileEntitySuite extends munit.FunSuite with LogCapturing:

  private val at              = Instant.parse("2026-10-27T10:00:00Z")
  private def hours(h: Long)  = at.plus(Duration.ofHours(h))
  private def days(d: Long)   = at.plus(Duration.ofDays(d))
  private val day             = hours(24)
  private val op              = Actor.operator("op-1")

  private def fresh = EventSourcedTestKit.of(ProfileEntity, "p-1")

  private def limit(t: String, v: String, actor: Actor = Actor.Player, currency: Option[String] = Some("EUR"), now: Instant = at) =
    SetLimit("p-1", t, currency, Some(v), actor, 60, now, now.plus(Duration.ofHours(24)))

  private def code(a: ProfileAnswer) = a.refusal.map(_.code)

  test("SetLimit's refusals, in order"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.setLimit)(limit("WAGER_DAILY", "5")).replyValue), Some("unknown_limit_type"))
    assertEquals(code(kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "5").copy(value = None)).replyValue), Some("value_required"))
    assertEquals(code(kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", " ")).replyValue), Some("value_required"))
    assertEquals(code(kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "12.5")).replyValue), Some("invalid_value"))
    assertEquals(code(kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "0")).replyValue), Some("invalid_value"))
    val below = kit.call(ProfileEntity.setLimit)(limit("SESSION_MINUTES", "30", currency = None)).replyValue
    assertEquals(below.refusal, Some(Refusal("below_minimum_session_minutes", Map("minimumMinutes" -> 60L))))
    assertEquals(kit.currentState, ProfileState())

  test("an operator's session limit has no minimum"):
    val kit = fresh
    assertEquals(kit.call(ProfileEntity.setLimit)(limit("SESSION_MINUTES", "30", op, None)).replyValue.state.sessionLimit, Some(30L))

  test("a first limit applies at once; a looser one is staged for 24 h; a tighter one applies and cancels it"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    assertEquals(kit.currentState.limit(LimitKey("DEPOSIT_DAILY", Some("EUR"))).map(_.value), Some(10_000L))
    val staged = kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "50000"))
    assertEquals(staged.events.collect { case e: PendingStaged => (e.value, e.appliesAt) }, Seq[(Option[Long], Instant)]((Some(50_000L), day)))
    assertEquals(kit.currentState.limit(LimitKey("DEPOSIT_DAILY", Some("EUR"))).map(_.value), Some(10_000L))
    val tighter = kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "5000"))
    assertEquals(tighter.events.map(_.getClass.getSimpleName), Seq("LimitSet", "PendingCancelled"))
    assertEquals(kit.currentState.pending, Map.empty)

  test("a new raise replaces the pending change"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "20000")): Unit
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "30000", now = hours(1))): Unit
    assertEquals(kit.currentState.pending.values.map(p => (p.value, p.appliesAt)).toSeq, Seq((Some(30_000L), hours(25))))

  test("limits are kept by currency"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "90000", currency = Some("GBP"))): Unit
    assertEquals(kit.currentState.moneyLimits("GBP"), Map(LimitType.DepositDaily -> 90_000L))
    assertEquals(kit.currentState.pending, Map.empty)

  test("an operator's change applies at once and cancels the pending change (FR-004)"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "50000")): Unit
    val r = kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "70000", op))
    assertEquals(r.events.map(_.getClass.getSimpleName), Seq("LimitSet", "PendingCancelled"))
    assertEquals(kit.currentState.moneyLimits("EUR"), Map(LimitType.DepositDaily -> 70_000L))

  test("a player's removal is staged whether or not a limit exists; an operator's needs one"):
    val kit = fresh
    val r   = kit.call(ProfileEntity.removeLimit)(RemoveLimit("p-1", "LOSS_DAILY", Some("EUR"), Actor.Player, at, day))
    assertEquals(r.events.collect { case e: PendingStaged => e.value }, Seq[Option[Long]](None))
    assertEquals(code(kit.call(ProfileEntity.removeLimit)(RemoveLimit("p-1", "LOSS_WEEKLY", Some("EUR"), op, at, day)).replyValue), Some("no_limit"))
    assertEquals(code(kit.call(ProfileEntity.removeLimit)(RemoveLimit("p-1", "NOPE", None, op, at, day)).replyValue), Some("unknown_limit_type"))

  test("the pending timer applies the change; a stale fire changes nothing"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "50000")): Unit
    assert(!kit.call(ProfileEntity.applyPending)(ApplyPending("DEPOSIT_DAILY/EUR", hours(1), hours(1))).persisted)
    assert(kit.call(ProfileEntity.applyPending)(ApplyPending("DEPOSIT_DAILY/EUR", day, day)).persisted)
    assertEquals(kit.currentState.moneyLimits("EUR"), Map(LimitType.DepositDaily -> 50_000L))
    assert(!kit.call(ProfileEntity.applyPending)(ApplyPending("DEPOSIT_DAILY/EUR", day, day)).persisted)

  test("a staged removal applies as a removal"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.removeLimit)(RemoveLimit("p-1", "DEPOSIT_DAILY", Some("EUR"), Actor.Player, at, day)): Unit
    kit.call(ProfileEntity.applyPending)(ApplyPending("DEPOSIT_DAILY/EUR", day, day)): Unit
    assertEquals(kit.currentState.limits, Map.empty)
    assertEquals(kit.currentState.history.last.eventType, "deposit_limit_removed")

  test("a break: bounds, a case opened, the player cannot change one that stands, an operator can"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.setBreak)(SetBreak("p-1", at.plusSeconds(3599), Actor.Player, 42, at)).replyValue), Some("break_too_short"))
    val long = kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(43), Actor.Player, 42, at)).replyValue
    assertEquals(long.refusal, Some(Refusal("break_too_long", Map("maximumDays" -> 42L))))
    val set = kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(7), Actor.Player, 42, at))
    assertEquals(set.events.map(_.getClass.getSimpleName), Seq("BreakSet", "CaseOpened"))
    val rgCase = kit.currentState.cases.head
    assertEquals((rgCase.caseType, rgCase.status, rgCase.closesAt, rgCase.trigger), ("cool_off", "open", Some(days(7)), "player"))
    assertEquals(code(kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(1), Actor.Player, 42, hours(1))).replyValue), Some("break_active"))
    val moved = kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(3), op, 42, hours(1)))
    assertEquals(moved.events.collect { case e: BreakSet => e.replaces }, Seq(true))
    assertEquals(kit.currentState.cases.map(c => (c.caseId, c.closesAt)), Vector((rgCase.caseId, Some(days(3)))))

  test("lifting a break needs one and a reason, and closes its case lifted"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.liftBreak)(LiftBreak("op-1", "x", at)).replyValue), Some("no_break"))
    kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(7), Actor.Player, 42, at)): Unit
    assertEquals(code(kit.call(ProfileEntity.liftBreak)(LiftBreak("op-1", " ", at)).replyValue), Some("reason_required"))
    kit.call(ProfileEntity.liftBreak)(LiftBreak("op-1", "called the player", hours(2))): Unit
    assertEquals(kit.currentState.break, None)
    assertEquals(kit.currentState.cases.map(c => (c.status, c.closeReason)), Vector(("closed", Some("lifted"))))

  test("the break timer ends the break and closes its case expired; a stale fire does nothing"):
    val kit = fresh
    kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(7), Actor.Player, 42, at)): Unit
    assert(!kit.call(ProfileEntity.endBreak)(EndBreak(days(6), days(6))).persisted)
    assert(kit.call(ProfileEntity.endBreak)(EndBreak(days(7), days(7))).persisted)
    assertEquals(kit.currentState.cases.map(_.closeReason), Vector(Some("expired")))
    assert(!kit.call(ProfileEntity.endBreak)(EndBreak(days(7), days(7))).persisted)

  test("an exclusion: periods as the original's parseDays, except an unreadable one"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.exclude)(Exclude("p-1", "six months", op, at)).replyValue), Some("invalid_period"))
    val short = kit.call(ProfileEntity.exclude)(Exclude("p-1", "P30D", op, at)).replyValue
    assertEquals(short.refusal, Some(Refusal("period_too_short", Map("minimumDays" -> 183L, "providedDays" -> 30L))))
    val r = kit.call(ProfileEntity.exclude)(Exclude("p-1", "183d", op, at))
    assertEquals(r.events.map(_.getClass.getSimpleName), Seq("Excluded", "CaseOpened"))
    assertEquals(kit.currentState.exclusion.flatMap(_.until), Some(days(183)))
    assertEquals(kit.currentState.playersTold, Some("self_excluded"))
    assertEquals(kit.currentState.cases.head.permanent, Some(false))
    assertEquals(code(kit.call(ProfileEntity.exclude)(Exclude("p-1", "permanent", Actor.Player, at)).replyValue), Some("already_excluded"))

  test("the return: refused when not excluded, permanent, before the end or already asked; reinstated 24 h after"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.requestReturn)(RequestReturn(Actor.Player, at, day)).replyValue), Some("not_excluded"))
    kit.call(ProfileEntity.exclude)(Exclude("p-1", "P183D", op, at)): Unit
    kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(7), op, 42, at)): Unit
    val early = kit.call(ProfileEntity.requestReturn)(RequestReturn(Actor.Player, days(10), days(11))).replyValue
    assertEquals(early.refusal, Some(Refusal("exclusion_not_ended", times = Map("endsAt" -> days(183)))))
    assert(kit.call(ProfileEntity.requestReturn)(RequestReturn(Actor.Player, days(183), days(184))).persisted)
    assertEquals(code(kit.call(ProfileEntity.requestReturn)(RequestReturn(Actor.Player, days(183), days(184))).replyValue), Some("return_requested"))
    assert(!kit.call(ProfileEntity.reinstate)(Reinstate(days(185), days(185))).persisted)
    assert(kit.call(ProfileEntity.reinstate)(Reinstate(days(184), days(184))).persisted)
    val s = kit.currentState
    assertEquals((s.exclusion, s.break, s.returnRequest, s.playersTold), (None, None, None, Some("active")))
    assertEquals(s.cases.map(_.status).distinct, Vector("closed"))

  test("a permanent exclusion cannot be returned from"):
    val kit = fresh
    kit.call(ProfileEntity.exclude)(Exclude("p-1", "permanent", Actor.Player, at)): Unit
    assertEquals(code(kit.call(ProfileEntity.requestReturn)(RequestReturn(Actor.Player, days(400), days(401))).replyValue), Some("exclusion_permanent"))

  test("players' acknowledgement clears only the status it acknowledges"):
    val kit = fresh
    kit.call(ProfileEntity.exclude)(Exclude("p-1", "permanent", Actor.Player, at)): Unit
    assert(!kit.call(ProfileEntity.playersAck)(PlayersAck("active", at)).persisted)
    assert(kit.call(ProfileEntity.playersAck)(PlayersAck("self_excluded", at)).persisted)
    assertEquals(kit.currentState.playersTold, None)

  test("reality checks: the four intervals, and a session not the player's refused"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.setRealityCheck)(SetRealityCheck(Some(20), at)).replyValue), Some("invalid_interval"))
    assertEquals(kit.call(ProfileEntity.setRealityCheck)(SetRealityCheck(Some(30), at)).replyValue.state.realityCheck, Some(30))
    assertEquals(kit.call(ProfileEntity.setRealityCheck)(SetRealityCheck(None, at)).replyValue.state.realityCheck, None)
    assertEquals(code(kit.call(ProfileEntity.acknowledge)(Acknowledge("gs-1", own = false, at)).replyValue), Some("session_not_found"))
    assertEquals(kit.call(ProfileEntity.acknowledge)(Acknowledge("gs-1", own = true, at)).replyValue.state.acknowledged, Some(Ack("gs-1", at)))

  test("cases: opened, noted and closed by an operator"):
    val kit = fresh
    assertEquals(code(kit.call(ProfileEntity.openCase)(OpenCase("p-1", "chat", "n", "op-1", at)).replyValue), Some("invalid_case_type"))
    val id = kit.call(ProfileEntity.openCase)(OpenCase("p-1", "account_block", "worried", "op-1", at)).replyValue.caseId.get
    assertEquals(code(kit.call(ProfileEntity.noteCase)(NoteCase("nope", "x", "op-1", at)).replyValue), Some("case_not_found"))
    kit.call(ProfileEntity.noteCase)(NoteCase(id, "called back", "op-2", hours(1))): Unit
    assertEquals(kit.currentState.caseOf(id).get.notes.map(_.text.reveal), Vector("worried", "called back"))
    kit.call(ProfileEntity.closeCase)(CloseCase(id, "resolved", "op-1", hours(2))): Unit
    assertEquals(code(kit.call(ProfileEntity.closeCase)(CloseCase(id, "again", "op-1", hours(3))).replyValue), Some("case_closed"))
    assertEquals(kit.currentState.caseOf(id).map(c => (c.status, c.closeReason)), Some(("closed", Some("closed"))))

  test("a GAMSTOP refusal is a third-party case with no end and no status change"):
    val kit = fresh
    assert(!kit.call(ProfileEntity.recordThirdParty)(RecordThirdParty("p-1", "timeout", at)).persisted)
    kit.call(ProfileEntity.recordThirdParty)(RecordThirdParty("p-1", "excluded", at)): Unit
    val c = kit.currentState.cases.head
    assertEquals((c.caseType, c.trigger, c.closesAt, kit.currentState.excluded), ("self_exclusion", "third_party", None, false))

  test("erasure blanks every note and reason, and keeps values, types, times and statuses"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(7), Actor.Player, 42, at)): Unit
    kit.call(ProfileEntity.liftBreak)(LiftBreak("op-1", "the player's mother called", hours(1))): Unit
    val id = kit.call(ProfileEntity.openCase)(OpenCase("p-1", "account_block", "lost a job", "op-1", at)).replyValue.caseId.get
    kit.call(ProfileEntity.erase)(Erase(hours(2))): Unit
    val s = kit.currentState
    assertEquals(s.cases.flatMap(_.notes.map(_.text.reveal)), Vector(""))
    assertEquals(s.cases.flatMap(_.closeNote.map(_.reveal)), Vector(""))
    assertEquals(s.caseOf(id).map(_.status), Some("open"))
    assertEquals(s.moneyLimits("EUR"), Map(LimitType.DepositDaily -> 10_000L))

  test("every change appends to the history, with its actor"):
    val kit = fresh
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "10000")): Unit
    kit.call(ProfileEntity.setLimit)(limit("DEPOSIT_DAILY", "20000")): Unit
    kit.call(ProfileEntity.setBreak)(SetBreak("p-1", days(1), op, 42, at)): Unit
    assertEquals(kit.currentState.history.map(h => (h.eventType, h.actor)),
      Vector(("deposit_limit_set", "player"), ("deposit_limit_change_requested", "player"), ("cooling_off_applied", "operator")))
