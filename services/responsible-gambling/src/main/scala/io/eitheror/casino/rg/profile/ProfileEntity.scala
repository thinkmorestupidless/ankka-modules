package io.eitheror.casino.rg.profile

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rg.RgCodecs

import java.time.{Duration, Instant}
import java.util.UUID

/**
 * A player's responsible-gambling profile (research R2): limits, pending changes, the break, the
 * exclusion and the return, the reality check, the cases and the history, one fold per command. The
 * refusals come in data-model.md's order. Timers are not this entity's: `ProfileTimers` reads its events.
 */
final class ProfileEntity(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[ProfileState, ProfileEvent]:
  import ProfileEvent.*
  import ProfileEntity.*

  def emptyState: ProfileState = ProfileState()

  def applyEvent(event: ProfileEvent): ProfileState =
    val s = currentState
    event match
      case e: LimitSet =>
        s.copy(playerId = e.playerId, limits = s.limits + (e.key.id -> Limit(e.key, e.value, e.at, e.actor)))
          .log(History.limit(e.key, "set", e.previous, Some(e.value), e.actor, e.at))
      case e: LimitRemoved =>
        s.copy(limits = s.limits - e.key.id).log(History.limit(e.key, "removed", Some(e.previous), None, e.actor, e.at))
      case e: PendingStaged =>
        s.copy(playerId = e.playerId, pending = s.pending + (e.key.id -> Pending(e.key, e.value, e.appliesAt, e.actor)))
          .log(History.limit(e.key, "change_requested", s.limit(e.key).map(_.value), e.value, e.actor, e.at))
      case e: PendingCancelled => s.copy(pending = s.pending - e.key.id)
      case e: PendingApplied =>
        val limits = e.value.fold(s.limits - e.key.id)(v => s.limits + (e.key.id -> Limit(e.key, v, e.at, Actor.System)))
        s.copy(limits = limits, pending = s.pending - e.key.id)
          .log(History.limit(e.key, if e.value.isEmpty then "removed" else "applied", e.previous, e.value, Actor.System, e.at))
      case e: BreakSet =>
        val withBreak = s.copy(playerId = e.playerId, break = Some(Break(e.until, e.at, e.caseId, e.actor)))
        if e.replaces then
          withBreak.updateCase(e.caseId)(_.copy(closesAt = Some(e.until)))
            .log(HistoryEntry("cooling_off_changed", None, None, Some(e.until.toString), e.actor.kind, e.at, s"Cooling-off period moved to ${e.until}"))
        else withBreak
      case _: BreakLifted => s.copy(break = None)
      case _: BreakEnded  => s.copy(break = None)
      case e: Excluded =>
        s.copy(playerId = e.playerId, exclusion = Some(Exclusion(e.permanent, e.until, e.at, e.trigger, e.caseId)),
          playersTold = Some(PlayersStatus.SelfExcluded))
      case e: ReturnRequested =>
        s.copy(returnRequest = Some(ReturnRequest(e.at, e.reinstatesAt, e.actor)))
          .log(HistoryEntry("return_requested", None, None, Some(e.reinstatesAt.toString), e.actor.kind, e.at, s"Return requested; reinstated at ${e.reinstatesAt}"))
      case e: Reinstated =>
        s.copy(exclusion = None, break = None, returnRequest = None, playersTold = Some(PlayersStatus.Active))
          .log(HistoryEntry("reinstated", None, None, None, Actor.System.kind, e.at, "Reinstated"))
      case e: PlayersAcknowledged => if s.playersTold.contains(e.status) then s.copy(playersTold = None) else s
      case e: RealityCheckSet =>
        s.copy(realityCheck = e.interval)
          .log(HistoryEntry("reality_check_set", None, e.previous.map(_.toString), e.interval.map(_.toString), Actor.Player.kind, e.at,
            e.interval.fold("Reality check removed")(i => s"Reality check every $i minutes")))
      case e: Acknowledged => s.copy(acknowledged = Some(Ack(e.sessionId, e.at)))
      case e: CaseOpened =>
        s.copy(playerId = e.playerId, cases = s.cases :+ e.rgCase).log(History.opened(e.rgCase, e.actor))
      case e: CaseNoted =>
        s.updateCase(e.caseId)(c => c.copy(notes = c.notes :+ CaseNote(e.at, e.operatorId, e.text)))
          .log(HistoryEntry("case_noted", None, None, None, Actor.OperatorKind, e.at, "A note was added to a case"))
      case e: CaseClosed =>
        s.updateCase(e.caseId)(_.copy(status = RgCase.Closed, closedAt = Some(e.at), closeReason = Some(e.reason), closeNote = e.note))
          .log(HistoryEntry(s"${History.caseName(e.caseType)}_${e.reason}", None, None, None, e.actor.kind, e.at, History.closed(e.caseType, e.reason)))
      case _: ThirdPartyExcluded => s
      case _: Erased =>
        s.copy(cases = s.cases.map(c => c.copy(notes = c.notes.map(_.copy(text = Blank)), closeNote = c.closeNote.map(_ => Blank))))

  private def reply(refusal: Refusal) = effects.reply(ProfileAnswer(currentState, Some(refusal)))
  private def refuse(code: String, numbers: (String, Long)*) = reply(Refusal(code, numbers.toMap))
  private def same = effects.reply(ProfileAnswer(currentState))
  private def answer(caseId: Option[String] = None)(s: ProfileState) = ProfileAnswer(s, None, caseId)

  private def cancelling(key: LimitKey, at: Instant): Seq[ProfileEvent] =
    if currentState.pending.contains(key.id) then Seq(PendingCancelled(key, at)) else Seq.empty

  // ── limits ──────────────────────────────────────────────────────────────

  def setLimit(c: SetLimit): Effect[ProfileAnswer] =
    LimitType.parse(c.limitType) match
      case None => refuse("unknown_limit_type")
      case Some(t) =>
        c.value.map(_.trim).filter(_.nonEmpty) match
          case None => refuse("value_required")
          case Some(text) =>
            Limits.parseValue(text) match
              case None => refuse("invalid_value")
              case Some(v) if t == LimitType.SessionMinutes && !c.actor.isOperator && v < c.minimumSessionMinutes =>
                refuse("below_minimum_session_minutes", "minimumMinutes" -> c.minimumSessionMinutes.toLong)
              case Some(v) =>
                val key     = LimitKey.of(t, c.currency)
                val current = currentState.limit(key).map(_.value)
                Limits.decide(c.actor.isOperator, current, Some(v)) match
                  case Limits.Decision.ApplyNow =>
                    effects.persist(LimitSet(c.playerId, key, v, current, c.actor, c.at), cancelling(key, c.at)*).thenReply(answer())
                  case Limits.Decision.Stage =>
                    effects.persist(PendingStaged(c.playerId, key, Some(v), c.appliesAt, c.actor, c.at)).thenReply(answer())

  def removeLimit(c: RemoveLimit): Effect[ProfileAnswer] =
    LimitType.parse(c.limitType) match
      case None => refuse("unknown_limit_type")
      case Some(t) =>
        val key = LimitKey.of(t, c.currency)
        if c.actor.isOperator then
          currentState.limit(key) match
            case None    => refuse("no_limit")
            case Some(l) => effects.persist(LimitRemoved(key, l.value, c.actor, c.at), cancelling(key, c.at)*).thenReply(answer())
        else effects.persist(PendingStaged(c.playerId, key, None, c.appliesAt, c.actor, c.at)).thenReply(answer())

  /** The pending timer's fire; one whose `appliesAt` no longer matches is stale and changes nothing. */
  def applyPending(c: ApplyPending): Effect[ProfileAnswer] =
    currentState.pending.get(c.keyId) match
      case Some(p) if p.appliesAt == c.appliesAt =>
        effects.persist(PendingApplied(p.key, p.value, currentState.limit(p.key).map(_.value), c.at)).thenReply(answer())
      case _ => same

  // ── breaks ──────────────────────────────────────────────────────────────

  def setBreak(c: SetBreak): Effect[ProfileAnswer] =
    val standing = currentState.onBreak(c.at)
    if standing.isDefined && !c.actor.isOperator then refuse("break_active")
    else if Duration.between(c.at, c.until).compareTo(Duration.ofHours(1)) < 0 then refuse("break_too_short")
    else if c.until.isAfter(c.at.plus(Duration.ofDays(c.maxBreakDays.toLong))) then refuse("break_too_long", "maximumDays" -> c.maxBreakDays.toLong)
    else
      standing match
        case Some(b) => effects.persist(BreakSet(c.playerId, c.until, b.caseId, c.actor, c.at, replaces = true)).thenReply(answer(Some(b.caseId)))
        case None =>
          val rgCase = RgCase(newId(), RgCase.CoolOff, trigger(c.actor), RgCase.Open, c.at, closesAt = Some(c.until))
          effects.persist(BreakSet(c.playerId, c.until, rgCase.caseId, c.actor, c.at), CaseOpened(c.playerId, rgCase, c.actor, c.at))
            .thenReply(answer(Some(rgCase.caseId)))

  def liftBreak(c: LiftBreak): Effect[ProfileAnswer] =
    currentState.break match
      case None => refuse("no_break")
      case Some(_) if c.reason.trim.isEmpty => refuse("reason_required")
      case Some(b) =>
        val actor = Actor.operator(c.operatorId)
        effects.persist(BreakLifted(c.operatorId, Personal(c.reason.trim), c.at),
          CaseClosed(b.caseId, RgCase.CoolOff, RgCase.Lifted, Some(Personal(c.reason.trim)), actor, c.at)).thenReply(answer(Some(b.caseId)))

  /** The break timer's fire; one for another break's end is stale and changes nothing. */
  def endBreak(c: EndBreak): Effect[ProfileAnswer] =
    currentState.break match
      case Some(b) if b.until == c.until =>
        effects.persist(BreakEnded(c.at), CaseClosed(b.caseId, RgCase.CoolOff, RgCase.Expired, None, Actor.System, c.at))
          .thenReply(answer(Some(b.caseId)))
      case _ => same

  // ── exclusion and return ────────────────────────────────────────────────

  def exclude(c: Exclude): Effect[ProfileAnswer] =
    if currentState.excluded then refuse("already_excluded")
    else
      Periods.parse(c.period) match
        case Periods.Unreadable => refuse("invalid_period")
        case Periods.Days(d) if d < Periods.MinimumDays =>
          refuse("period_too_short", "minimumDays" -> Periods.MinimumDays.toLong, "providedDays" -> d.toLong)
        case p =>
          val until     = p match
            case Periods.Days(d) => Some(c.at.plus(Duration.ofDays(d.toLong)))
            case _               => None
          val permanent = until.isEmpty
          val rgCase    = RgCase(newId(), RgCase.SelfExclusion, trigger(c.actor), RgCase.Open, c.at, closesAt = until,
            permanent = Some(permanent))
          effects.persist(Excluded(c.playerId, permanent, until, rgCase.trigger, rgCase.caseId, c.actor, c.at),
            CaseOpened(c.playerId, rgCase, c.actor, c.at)).thenReply(answer(Some(rgCase.caseId)))

  def requestReturn(c: RequestReturn): Effect[ProfileAnswer] =
    currentState.exclusion match
      case None                           => refuse("not_excluded")
      case Some(e) if e.permanent         => refuse("exclusion_permanent")
      case Some(e) if e.until.exists(_.isAfter(c.at)) =>
        reply(Refusal("exclusion_not_ended", times = Map("endsAt" -> e.until.get)))
      case Some(_) if currentState.returnRequest.isDefined => refuse("return_requested")
      case Some(_) => effects.persist(ReturnRequested(c.reinstatesAt, c.actor, c.at)).thenReply(answer())

  /** The return timer's fire: the exclusion and any break cleared, their open cases closed (FR-020). */
  def reinstate(c: Reinstate): Effect[ProfileAnswer] =
    currentState.returnRequest match
      case Some(r) if r.reinstatesAt == c.reinstatesAt =>
        val closing = currentState.cases.filter(rc => rc.open && (rc.caseType == RgCase.SelfExclusion || rc.caseType == RgCase.CoolOff))
          .map(rc => CaseClosed(rc.caseId, rc.caseType, RgCase.Expired, None, Actor.System, c.at))
        effects.persist(Reinstated(c.at), closing*).thenReply(answer())
      case _ => same

  def playersAck(c: PlayersAck): Effect[ProfileAnswer] =
    if currentState.playersTold.contains(c.status) then effects.persist(PlayersAcknowledged(c.status, c.at)).thenReply(answer())
    else same

  // ── reality checks ──────────────────────────────────────────────────────

  def setRealityCheck(c: SetRealityCheck): Effect[ProfileAnswer] =
    if c.interval.exists(i => !RealityChecks.Allowed.contains(i)) then refuse("invalid_interval")
    else if c.interval == currentState.realityCheck then same
    else effects.persist(RealityCheckSet(c.interval, currentState.realityCheck, c.at)).thenReply(answer())

  def acknowledge(c: Acknowledge): Effect[ProfileAnswer] =
    if !c.own then refuse("session_not_found")
    else effects.persist(Acknowledged(c.sessionId, c.at)).thenReply(answer())

  // ── cases ───────────────────────────────────────────────────────────────

  def openCase(c: OpenCase): Effect[ProfileAnswer] =
    if !RgCase.Types.contains(c.caseType) then refuse("invalid_case_type")
    else
      val actor  = Actor.operator(c.operatorId)
      val rgCase = RgCase(newId(), c.caseType, Actor.OperatorKind, RgCase.Open, c.at,
        notes = Vector(CaseNote(c.at, c.operatorId, Personal(c.note))).filter(_.text.reveal.trim.nonEmpty))
      effects.persist(CaseOpened(c.playerId, rgCase, actor, c.at)).thenReply(answer(Some(rgCase.caseId)))

  def noteCase(c: NoteCase): Effect[ProfileAnswer] =
    currentState.caseOf(c.caseId) match
      case None => refuse("case_not_found")
      case Some(_) => effects.persist(CaseNoted(c.caseId, c.operatorId, Personal(c.text), c.at)).thenReply(answer(Some(c.caseId)))

  def closeCase(c: CloseCase): Effect[ProfileAnswer] =
    currentState.caseOf(c.caseId) match
      case None                 => refuse("case_not_found")
      case Some(rc) if !rc.open => refuse("case_closed")
      case Some(rc) =>
        val note = Option(c.reason.trim).filter(_.nonEmpty).map(Personal(_))
        effects.persist(CaseClosed(rc.caseId, rc.caseType, RgCase.ClosedReason, note, Actor.operator(c.operatorId), c.at))
          .thenReply(answer(Some(rc.caseId)))

  /** A GAMSTOP refusal (FR-022): a third-party self-exclusion case, no end, no status change; other results change nothing. */
  def recordThirdParty(c: RecordThirdParty): Effect[ProfileAnswer] =
    if c.result != "excluded" then same
    else
      val rgCase = RgCase(newId(), RgCase.SelfExclusion, Actor.ThirdParty.kind, RgCase.Open, c.at)
      effects.persist(ThirdPartyExcluded(rgCase.caseId, c.result, c.at), CaseOpened(c.playerId, rgCase, Actor.ThirdParty, c.at))
        .thenReply(answer(Some(rgCase.caseId)))

  /** Every personal value blanked; limits, case types, times and statuses kept (FR-032). */
  def erase(c: Erase): Effect[ProfileAnswer] = effects.persist(Erased(c.at)).thenReply(answer())

  def get: ReadOnlyEffect[ProfileState] = effects.reply(currentState)

object ProfileEntity
    extends EventSourcedEntity.Companion[ProfileEntity, ProfileState, ProfileEvent](
      componentId = ComponentId("rg-profile"),
      stateSerializer = RgCodecs.serializer[ProfileState]("profile-state"),
      eventSerializer = RgCodecs.serializer[ProfileEvent]("profile-event")
    ):
  val Blank: Personal[String] = Personal("")

  private[profile] def newId(): String = UUID.randomUUID().toString

  private[profile] def trigger(actor: Actor): String = actor.kind

  given Serializer[ProfileState]    = RgCodecs.serializer[ProfileState]("profile-reply")
  given Serializer[ProfileAnswer]   = RgCodecs.serializer[ProfileAnswer]("profile-answer")
  given Serializer[SetLimit]        = RgCodecs.serializer[SetLimit]("set-limit")
  given Serializer[RemoveLimit]     = RgCodecs.serializer[RemoveLimit]("remove-limit")
  given Serializer[ApplyPending]    = RgCodecs.serializer[ApplyPending]("apply-pending")
  given Serializer[SetBreak]        = RgCodecs.serializer[SetBreak]("set-break")
  given Serializer[LiftBreak]       = RgCodecs.serializer[LiftBreak]("lift-break")
  given Serializer[EndBreak]        = RgCodecs.serializer[EndBreak]("end-break")
  given Serializer[Exclude]         = RgCodecs.serializer[Exclude]("exclude")
  given Serializer[RequestReturn]   = RgCodecs.serializer[RequestReturn]("request-return")
  given Serializer[Reinstate]       = RgCodecs.serializer[Reinstate]("reinstate")
  given Serializer[PlayersAck]      = RgCodecs.serializer[PlayersAck]("players-ack")
  given Serializer[SetRealityCheck] = RgCodecs.serializer[SetRealityCheck]("set-reality-check")
  given Serializer[Acknowledge]     = RgCodecs.serializer[Acknowledge]("acknowledge")
  given Serializer[OpenCase]        = RgCodecs.serializer[OpenCase]("open-case")
  given Serializer[NoteCase]        = RgCodecs.serializer[NoteCase]("note-case")
  given Serializer[CloseCase]       = RgCodecs.serializer[CloseCase]("close-case")
  given Serializer[RecordThirdParty] = RgCodecs.serializer[RecordThirdParty]("record-third-party")
  given Serializer[Erase]           = RgCodecs.serializer[Erase]("erase")

  def create(context: EventSourcedEntityContext) = new ProfileEntity(context)

  val setLimit         = command("set-limit")(_.setLimit)
  val removeLimit      = command("remove-limit")(_.removeLimit)
  val applyPending     = command("apply-pending")(_.applyPending)
  val setBreak         = command("set-break")(_.setBreak)
  val liftBreak        = command("lift-break")(_.liftBreak)
  val endBreak         = command("end-break")(_.endBreak)
  val exclude          = command("exclude")(_.exclude)
  val requestReturn    = command("request-return")(_.requestReturn)
  val reinstate        = command("reinstate")(_.reinstate)
  val playersAck       = command("players-ack")(_.playersAck)
  val setRealityCheck  = command("set-reality-check")(_.setRealityCheck)
  val acknowledge      = command("acknowledge")(_.acknowledge)
  val openCase         = command("open-case")(_.openCase)
  val noteCase         = command("note-case")(_.noteCase)
  val closeCase        = command("close-case")(_.closeCase)
  val recordThirdParty = command("record-third-party")(_.recordThirdParty)
  val erase            = command("erase")(_.erase)
  val get              = query("get")(_.get)

/** The reality check's intervals (FR-023). */
object RealityChecks:
  val Allowed: Vector[Int] = Vector(15, 30, 45, 60)

/** An operator's exclusion period, as the original's `parseDays`, except that an unreadable one is refused (R16). */
object Periods:
  val MinimumDays = 183

  sealed trait Period
  case object Permanent  extends Period
  case object Unreadable extends Period
  final case class Days(days: Int) extends Period

  def parse(text: String): Period =
    val p = text.trim
    if p.equalsIgnoreCase("permanent") then Permanent
    else if p.startsWith("P") && p.endsWith("D") then
      scala.util.Try(java.time.Period.parse(p)).toOption.filter(x => x.getYears == 0 && x.getMonths == 0)
        .map(x => Days(x.getDays)).getOrElse(Unreadable)
    else if p.endsWith("d") || p.endsWith("D") then p.dropRight(1).toIntOption.filter(_ >= 0).map(Days(_)).getOrElse(Unreadable)
    else Unreadable

/** The history's lines (FR-027), in the original's `eventType` names for cases and new ones for limit changes. */
object History:
  def caseName(caseType: String): String = caseType match
    case RgCase.CoolOff => "cooling_off"
    case other          => other

  def opened(c: RgCase, actor: Actor): HistoryEntry =
    val eventType = c.caseType match
      case RgCase.SelfExclusion => "self_exclusion_applied"
      case RgCase.CoolOff       => "cooling_off_applied"
      case other                => s"${other}_case_opened"
    val description = c.caseType match
      case RgCase.SelfExclusion if c.trigger == Actor.ThirdParty.kind => "Self-exclusion reported by a third party"
      case RgCase.SelfExclusion => c.closesAt.fold("Permanent self-exclusion applied")(u => s"Self-exclusion applied until $u")
      case RgCase.CoolOff       => s"Cooling-off period applied${c.closesAt.fold("")(u => s" until $u")}"
      case other                => s"RG case opened: $other"
    HistoryEntry(eventType, None, None, c.closesAt.map(_.toString), actor.kind, c.openedAt, description)

  def closed(caseType: String, reason: String): String = s"${caseName(caseType).replace('_', ' ').capitalize} case closed: $reason"

  def limit(key: LimitKey, change: String, previous: Option[Long], next: Option[Long], actor: Actor, at: Instant): HistoryEntry =
    val t = key.kind
    val kind = t.kind match
      case LimitType.Kind.Deposit => "deposit_limit"
      case LimitType.Kind.Loss    => "loss_limit"
      case LimitType.Kind.Session => "session_limit"
    val what = s"${t.name}${key.currency.fold("")(c => s" ($c)")}"
    HistoryEntry(s"${kind}_$change", Some(t.name), previous.map(_.toString), next.map(_.toString), actor.kind, at,
      s"$what ${change.replace('_', ' ')}${next.fold("")(v => s": $v")}")

extension (s: ProfileState)
  private[profile] def log(entry: HistoryEntry): ProfileState = s.copy(history = s.history :+ entry)
  private[profile] def updateCase(caseId: String)(f: RgCase => RgCase): ProfileState =
    s.copy(cases = s.cases.map(c => if c.caseId == caseId then f(c) else c))
