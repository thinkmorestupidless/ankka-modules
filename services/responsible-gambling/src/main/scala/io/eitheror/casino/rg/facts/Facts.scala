package io.eitheror.casino.rg.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.rg.profile.{Actor, LimitKey, RgCase}
import io.eitheror.casino.rg.profile.ProfileEvent
import io.eitheror.casino.rg.profile.ProfileEvent.*
import io.eitheror.casino.rg.settings.SettingsChanged

import java.time.Instant

/**
 * The profile's events as facts (contracts/facts.md), pure. The CloudEvents id is the event's id,
 * `profile/<player>/<sequence>`, so a redelivered event is the same fact.
 */
object Facts:
  final case class Published[F](fact: F, id: String)

  def eventId(component: String, playerId: String, sequence: Long): String = s"$component/$playerId/$sequence"

  // ── casino.players ──────────────────────────────────────────────────────

  def players(playerId: String, sequence: Long, event: ProfileEvent): Option[Published[f.CasinoPlayersFact]] =
    def applied(key: LimitKey, value: Option[Long], causedBy: String, at: Instant) =
      f.RgLimitApplied(playerId, key.limitType, key.kind.period, value, causedBy, at, key.currency)
    val fact: Option[f.CasinoPlayersFact] = event match
      case e: LimitSet =>
        val causedBy = if e.previous.exists(e.value < _) then "lowered" else "set"
        Some(applied(e.key, Some(e.value), causedBy, e.at))
      case e: PendingApplied => Some(applied(e.key, e.value, if e.value.isEmpty then "removed" else "applied", e.at))
      case e: LimitRemoved   => Some(applied(e.key, None, "removed", e.at))
      case e: CaseOpened =>
        val c = e.rgCase
        Some(f.RgCaseOpened(playerId, c.caseId, c.caseType, c.trigger, c.closesAt, e.at,
          if c.caseType == RgCase.SelfExclusion then Some(c.permanent.getOrElse(c.closesAt.isEmpty)) else None))
      case e: CaseClosed => Some(f.RgCaseClosed(playerId, e.caseId, e.caseType, e.reason, e.at))
      case _             => None
    fact.map(Published(_, eventId("profile", playerId, sequence)))

  // ── platform.audit ──────────────────────────────────────────────────────

  private def change(field: String, before: Option[String], after: Option[String]) = f.AuditChange(field, before, after)

  /** A field whose value is personal: named, with no value (spec 018 FR-003; FR-032). */
  private def named(field: String) = f.AuditChange(field, None, None)

  private def limitField(key: LimitKey) = key.currency.fold(key.limitType)(c => s"${key.limitType}/$c")

  /** A change to the brand's settings (FR-028, FR-029): the brand is its subject, each changed field its before and after. */
  def settingsAudit(brand: String, sequence: Long, event: SettingsChanged): Published[f.AuditRecord] =
    val id = eventId("audit/settings", brand, sequence)
    val changes = Vector(
      ("minSessionMinutes", event.before.minSessionMinutes, event.after.minSessionMinutes),
      ("maxBreakDays", event.before.maxBreakDays, event.after.maxBreakDays)
    ).collect { case (field, b, a) if b != a => change(field, Some(b.toString), Some(a.toString)) }
    Published(f.AuditRecord(recordId = id, brand = brand, service = "responsible-gambling", action = "settings-change", actorKind = "operator",
      actorId = event.operatorId, approverId = None, subjectKind = "brand", subjectId = brand, outcome = "accepted", reasonCode = None,
      changes = changes, correlationId = id, causationId = id, recordClass = "operator", occurredAt = event.at), id)

  /** One record per operator change (FR-028); a player's or the system's changes are not audited here. */
  def audit(brand: String, playerId: String, sequence: Long, event: ProfileEvent): Option[Published[f.AuditRecord]] =
    def op(actor: Actor, action: String, changes: Vector[f.AuditChange]) =
      actor.id.filter(_ => actor.isOperator).map(id => (id, action, changes))
    val action = event match
      case e: LimitSet     => op(e.actor, "limit-set", Vector(change(limitField(e.key), e.previous.map(_.toString), Some(e.value.toString))))
      case e: LimitRemoved => op(e.actor, "limit-remove", Vector(change(limitField(e.key), Some(e.previous.toString), None)))
      case e: BreakSet     => op(e.actor, "break-set", Vector(change("coolingOffUntil", None, Some(e.until.toString))))
      case e: BreakLifted  => op(Actor.operator(e.operatorId), "break-lift", Vector(change("coolingOffUntil", None, None), named("reason")))
      case e: Excluded     =>
        op(e.actor, "exclude", Vector(change("exclusion", None, Some(e.until.fold("permanent")(_.toString)))))
      case e: ReturnRequested => op(e.actor, "return-request", Vector(change("reinstatesAt", None, Some(e.reinstatesAt.toString))))
      case e: CaseOpened if e.rgCase.caseType != RgCase.CoolOff && e.rgCase.caseType != RgCase.SelfExclusion =>
        op(e.actor, "case-open", Vector(change("case", None, Some(s"${e.rgCase.caseType}/${e.rgCase.caseId}"))) ++
          Option.when(e.rgCase.notes.nonEmpty)(named("note")))
      case e: CaseNoted => op(Actor.operator(e.operatorId), "case-note", Vector(change("case", None, Some(e.caseId)), named("note")))
      case e: CaseClosed if e.reason == RgCase.ClosedReason =>
        op(e.actor, "case-close", Vector(change("case", Some("open"), Some("closed"))) ++ e.note.map(_ => named("reason")))
      case _ => None
    action.map { (actorId, name, changes) =>
      val id = eventId("audit/profile", playerId, sequence)
      Published(f.AuditRecord(recordId = id, brand = brand, service = "responsible-gambling", action = name, actorKind = "operator",
        actorId = actorId, approverId = None, subjectKind = "player", subjectId = playerId, outcome = "accepted", reasonCode = None,
        changes = changes, correlationId = id, causationId = id, recordClass = "operator", occurredAt = event.at), id)
    }
