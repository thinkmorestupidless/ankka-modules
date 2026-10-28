package io.eitheror.casino.rg.facts

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rg.profile.*
import io.eitheror.casino.rg.profile.ProfileEvent.*
import io.eitheror.casino.rg.settings.{BrandSettings, BrandSettingsEntity, ChangeSettings, SettingsChanged}

import java.time.Instant

/** Every event to its family or none (contracts/facts.md), and the audit's records (T013); the brand's settings (T009). */
class PublishersSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-27T12:00:00Z")
  private val eur = LimitKey("DEPOSIT_DAILY", Some("EUR"))
  private val op  = Actor.operator("op-1")

  private def fact(e: ProfileEvent) = Facts.players("p-1", 7, e).map(_.fact)
  private def audit(e: ProfileEvent) = Facts.audit("goldvibe", "p-1", 7, e).map(_.fact)

  test("a limit set: set where none was, lowered where it is smaller, with its period and currency"):
    assertEquals(fact(LimitSet("p-1", eur, 5_000, None, Actor.Player, at)),
      Some(f.RgLimitApplied("p-1", "DEPOSIT_DAILY", Some("daily"), Some(5_000), "set", at, Some("EUR"))))
    assertEquals(fact(LimitSet("p-1", eur, 5_000, Some(9_000), Actor.Player, at)).collect { case x: f.RgLimitApplied => x.causedBy }, Some("lowered"))
    assertEquals(fact(LimitSet("p-1", eur, 9_000, Some(5_000), op, at)).collect { case x: f.RgLimitApplied => x.causedBy }, Some("set"))

  test("a pending change applied, a removal applied, an operator's removal"):
    assertEquals(fact(PendingApplied(eur, Some(9_000), Some(5_000), at)).collect { case x: f.RgLimitApplied => x.causedBy }, Some("applied"))
    assertEquals(fact(PendingApplied(eur, None, Some(5_000), at)).collect { case x: f.RgLimitApplied => (x.causedBy, x.value) }, Some(("removed", None)))
    assertEquals(fact(LimitRemoved(eur, 5_000, op, at)).collect { case x: f.RgLimitApplied => x.causedBy }, Some("removed"))

  test("a session limit carries no period or currency, as players' consumer reads it"):
    val k = LimitKey("SESSION_MINUTES", None)
    assertEquals(fact(LimitSet("p-1", k, 90, None, Actor.Player, at)),
      Some(f.RgLimitApplied("p-1", "SESSION_MINUTES", None, Some(90), "set", at, None)))

  test("a staged change and the other events publish nothing"):
    Seq[ProfileEvent](PendingStaged("p-1", eur, Some(1), at, Actor.Player, at), PendingCancelled(eur, at), BreakSet("p-1", at, "c", Actor.Player, at),
      ReturnRequested(at, Actor.Player, at), RealityCheckSet(Some(15), None, at), Erased(at)).foreach(e => assertEquals(fact(e), None, e.toString))

  test("a case opened carries permanent on a self-exclusion only; a case closed its closure"):
    val perm = RgCase("c1", RgCase.SelfExclusion, "player", "open", at, permanent = Some(true))
    assertEquals(fact(CaseOpened("p-1", perm, Actor.Player, at)), Some(f.RgCaseOpened("p-1", "c1", "self_exclusion", "player", None, at, Some(true))))
    val gamstop = RgCase("c2", RgCase.SelfExclusion, "third_party", "open", at)
    assertEquals(fact(CaseOpened("p-1", gamstop, Actor.ThirdParty, at)).collect { case x: f.RgCaseOpened => x.permanent }, Some(Some(true)))
    val brk = RgCase("c3", RgCase.CoolOff, "player", "open", at, closesAt = Some(at.plusSeconds(7200)))
    assertEquals(fact(CaseOpened("p-1", brk, Actor.Player, at)).collect { case x: f.RgCaseOpened => x.permanent }, Some(None))
    assertEquals(fact(CaseClosed("c3", RgCase.CoolOff, "expired", None, Actor.System, at)), Some(f.RgCaseClosed("p-1", "c3", "cool_off", "expired", at)))

  test("the CloudEvents id is the event's"):
    assertEquals(Facts.players("p-1", 7, LimitRemoved(eur, 5, op, at)).map(_.id), Some("profile/p-1/7"))

  test("an operator's change is one audit record; a player's none"):
    assertEquals(audit(LimitSet("p-1", eur, 5_000, None, Actor.Player, at)), None)
    val r = audit(LimitSet("p-1", eur, 5_000, Some(9_000), op, at)).get
    assertEquals((r.service, r.action, r.actorId, r.subjectId, r.changes),
      ("responsible-gambling", "limit-set", "op-1", "p-1", Vector(f.AuditChange("DEPOSIT_DAILY/EUR", Some("9000"), Some("5000")))))

  test("a note or a reason is named on the audit, never written"):
    val secret = "the player's mother called"
    Seq[ProfileEvent](
      BreakLifted("op-1", Personal(secret), at),
      CaseNoted("c1", "op-1", Personal(secret), at),
      CaseClosed("c1", "account_block", "closed", Some(Personal(secret)), op, at),
      CaseOpened("p-1", RgCase("c1", "account_block", "operator", "open", at, notes = Vector(CaseNote(at, "op-1", Personal(secret)))), op, at)
    ).foreach { e =>
      val r = audit(e).getOrElse(fail(s"no record for $e"))
      assert(!r.toString.contains(secret), r.toString)
      assert(r.changes.exists(c => (c.field == "note" || c.field == "reason") && c.before.isEmpty && c.after.isEmpty), r.toString)
    }

  test("the brand's settings: defaults 60 and 42, a field changed alone, a bad value refused naming it"):
    val kit = EventSourcedTestKit.of(BrandSettingsEntity, "goldvibe")
    assertEquals(kit.currentState, BrandSettings(60, 42))
    val at      = Instant.parse("2026-10-27T12:00:00Z")
    val changed = kit.call(BrandSettingsEntity.change)(ChangeSettings(minSessionMinutes = Some(30), operatorId = "op-1", at = at))
    assertEquals(changed.replyValue.settings, BrandSettings(30, 42))
    assertEquals(changed.events, Vector(SettingsChanged(BrandSettings(60, 42), BrandSettings(30, 42), "op-1", at)))
    val bad = kit.call(BrandSettingsEntity.change)(ChangeSettings(maxBreakDays = Some(0)))
    assertEquals((bad.replyValue.refusal, bad.replyValue.field, bad.replyValue.settings), (Some("invalid_setting"), Some("maxBreakDays"), BrandSettings(30, 42)))
    assertEquals(bad.events, Vector.empty)
    assertEquals(kit.call(BrandSettingsEntity.change)(ChangeSettings(minSessionMinutes = Some(30))).events, Vector.empty, "no change, nothing to audit")

  test("a settings change is audited against the brand with each changed field's before and after"):
    val at = Instant.parse("2026-10-27T12:00:00Z")
    val r  = Facts.settingsAudit("goldvibe", 3, SettingsChanged(BrandSettings(60, 42), BrandSettings(45, 42), "op-1", at)).fact
    assertEquals((r.action, r.actorId, r.subjectKind, r.subjectId, r.changes),
      ("settings-change", "op-1", "brand", "goldvibe", Vector(f.AuditChange("minSessionMinutes", Some("60"), Some("45")))))
