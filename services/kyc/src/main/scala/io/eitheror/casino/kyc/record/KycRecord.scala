package io.eitheror.casino.kyc.record

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.KycCodecs.given
import io.eitheror.casino.kyc.documents.ObjectNames
import io.eitheror.casino.kyc.record.KycEvent.*
import io.eitheror.casino.kyc.record.Rollup.Trigger

import java.time.{Duration, Instant, LocalDate, ZoneOffset}

/**
 * One player's KYC record (R3), keyed by the player id: documents, offers, status and mode, required
 * categories, source of funds, flags, the deadline and lifetime deposits. Every rule reads several of
 * these together, and every change is one event. After a command that changes an input of the derivation
 * the record derives (R4) and writes only what changed (FR-014).
 */
final class KycRecord(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[KycState, KycEvent]:
  import KycRecord.*

  def emptyState: KycState = KycState()

  def applyEvent(event: KycEvent): KycState = applied(currentState, event)

  /** Persist the events, or reply with the state as it stands when there are none. */
  private def emit(events: Vector[KycEvent], refusal: Option[Refusal] = None, gate: Option[GateAnswer] = None): Effect[KycAnswer] =
    events match
      case first +: rest => effects.persist(first, rest*).thenReply(KycAnswer(_, refusal, gate))
      case _             => effects.reply(KycAnswer(currentState, refusal, gate))

  private def refuse(code: String, fields: Vector[String] = Vector.empty): Effect[KycAnswer] =
    effects.reply(KycAnswer(currentState, Some(Refusal(code, fields))))

  /** The events, then the derivation's on the state they leave, then the offers an hour old. */
  private def withDerivation(events: Vector[KycEvent], trigger: Trigger, policyRequired: Vector[Category], at: Instant): Vector[KycEvent] =
    val after = events.foldLeft(currentState)(applied)
    events ++ derived(after, trigger, policyRequired, at) ++ expired(currentState, at)

  def requestUpload(c: RequestUpload): Effect[KycAnswer] =
    val s = currentState
    Category.of(c.category).filter(Category.Uploadable.contains) match
      case _ if s.closed || s.erased                => refuse("account_closed")
      case None                                     => refuse("invalid_category")
      case Some(_) if !ObjectNames.accepted(c.contentType) => refuse("file_rejected", Vector("contentType"))
      case Some(category) if category != Category.Other && s.latest(category).exists(d => !d.review.exists(_.outcome == Outcome.Rejected)) =>
        refuse("document_locked")
      case Some(category) =>
        val name  = ObjectNames.of(c.playerId, category, c.at, c.contentType).get
        val offer = Offer(c.documentId, category, c.contentType, name, c.by, c.at)
        emit(UploadOffered(c.playerId, offer, c.at) +: expired(s, c.at))

  def confirmUpload(c: ConfirmUpload): Effect[KycAnswer] =
    currentState.offers.get(c.documentId) match
      case None                                      => refuse("document_not_found")
      case Some(o) if isExpired(o, c.at)             => emit(expired(currentState, c.at), Some(Refusal("upload_expired")))
      case Some(o) if o.contentType != c.contentType => refuse("file_rejected", Vector("contentType"))
      case Some(o) =>
        val document = Document(o.documentId, o.category, o.objectName, c.contentType, c.size, c.at, o.by)
        emit(withDerivation(Vector(DocumentRecorded(currentState.playerId, document, c.at)), Trigger.Documents, c.policyRequired, c.at))

  /** The reason code is checked first, as the original does (R8). */
  def review(c: ReviewDocument): Effect[KycAnswer] =
    val reason = c.reasonCode.flatMap(ReasonCode.of)
    if c.outcome == Outcome.Rejected && reason.isEmpty then refuse("invalid_rejection_reason")
    else
      currentState.document(c.documentId) match
        case None                       => refuse("document_not_found")
        case Some(d) if d.review.isDefined => refuse("document_already_reviewed")
        case Some(d) =>
          val r = Review(c.outcome, if c.outcome == Outcome.Rejected then reason else None, c.operatorId, c.at)
          emit(withDerivation(Vector(DocumentReviewed(d.id, d.category, r, c.at)), Trigger.Documents, c.policyRequired, c.at))

  def issueView(c: IssueView): Effect[KycAnswer] =
    currentState.document(c.documentId) match
      case None                          => refuse("document_not_found")
      case Some(_) if currentState.erased => refuse("document_erased")
      case Some(d)                       => emit(Vector(ViewIssued(d.id, c.requester, c.at)))

  /** A switch to automatic clears the ready time and derives at once (FR-015); either switch derives. */
  def setMode(c: SetMode): Effect[KycAnswer] =
    val s = currentState
    if c.mode == s.mode then emit(Vector.empty)
    else
      val cleared = Option.when(c.mode == Mode.Automatic && s.readyAt.isDefined)(ReadyCleared(c.at))
      emit(withDerivation(ModeChanged(c.mode, s.mode, c.operatorId, c.at) +: cleared.toVector, Trigger.Documents, c.policyRequired, c.at))

  /** An operator's status, pending or verified; verified refused while the profile is incomplete (FR-015). */
  def setStatus(c: SetStatus): Effect[KycAnswer] =
    val s       = currentState
    val missing = s.missingProfileFields(today(c.at))
    if c.status == Status.Verified && missing.nonEmpty then refuse("profile_incomplete", missing.map(_.wire))
    else if c.status == s.status then emit(Vector.empty)
    else emit(byOperator(StatusChanged(s.status, c.status, Actor.operator(c.operatorId), "agent", None, c.at), c.policyRequired))

  /** The original's approve and reject, regardless of the documents and the profile (R8). */
  def overrideStatus(c: Override): Effect[KycAnswer] =
    val s = currentState
    if c.status == s.status then emit(Vector.empty)
    else
      val note = c.reason.map(_.trim).filter(_.nonEmpty).map(Personal(_))
      emit(byOperator(StatusChanged(s.status, c.status, Actor.operator(c.operatorId), "override", note, c.at), c.policyRequired))

  /** An operator's status stands in automatic mode; in manual mode the ready time follows it (FR-018). */
  private def byOperator(change: StatusChanged, policyRequired: Vector[Category]): Vector[KycEvent] =
    if currentState.mode == Mode.Manual then withDerivation(Vector(change), Trigger.Documents, policyRequired, change.at)
    else Vector(change) ++ expired(currentState, change.at)

  /**
   * The categories required of this player (FR-017). A non-empty list puts the player in manual mode and
   * the status to pending unless it is pending or rejected.
   */
  def setRequired(c: SetRequired): Effect[KycAnswer] =
    val s      = currentState
    val parsed = c.categories.distinct.map(n => n -> Category.of(n))
    parsed.collectFirst { case (n, None) => n } match
      case Some(unknown) => refuse("invalid_category", Vector(unknown))
      case None =>
        val categories = parsed.flatMap(_._2)
        if categories == s.required then emit(Vector.empty)
        else
          val manual  = Option.when(categories.nonEmpty && s.mode != Mode.Manual)(ModeChanged(Mode.Manual, s.mode, c.operatorId, c.at))
          val pending = Option.when(categories.nonEmpty && s.status != Status.Pending && s.status != Status.Rejected)(
            StatusChanged(s.status, Status.Pending, Actor.operator(c.operatorId), "required_category", None, c.at))
          val events = RequiredChanged(categories, s.required, c.operatorId, c.at) +: (manual.toVector ++ pending)
          emit(withDerivation(events, Trigger.Documents, c.policyRequired, c.at))

  def clearSof(c: ClearSof): Effect[KycAnswer] =
    if currentState.sof == Sof.Cleared then emit(Vector.empty) else emit(Vector(SofCleared(c.operatorId, c.at)))

  def resetSof(c: ResetSof): Effect[KycAnswer] =
    if currentState.sof == Sof.Unverified then emit(Vector.empty) else emit(Vector(SofReset(c.operatorId, c.at)))

  def dismissFlag(c: DismissFlag): Effect[KycAnswer] =
    FlagReason.of(c.reason).flatMap(currentState.openFlag) match
      case None                         => refuse("flag_not_found")
      case Some(_) if c.note.trim.isEmpty => refuse("note_required")
      case Some(f)                      => emit(Vector(FlagDismissed(f.reason, c.operatorId, Personal(c.note.trim), c.at)))

  /** The deposit check (R5): the answer and the flag it raises, once, are one command. */
  def depositCheck(c: DepositCheck): Effect[KycAnswer] =
    val s    = currentState
    val gate = Gates.deposit(c.policy, s.status, s.sof, s.lifetime(c.policy.currency), c.currency, c.amount)
    emit(flag(s, c.playerId, gate.flag, c.at).toVector, gate = Some(gate))

  /**
   * A confirmed deposit, counted once by the wallet's ordinal (R6); one whose own amount in the KYC
   * currency exceeds the deposit threshold flags a player not verified (FR-038).
   */
  def countDeposit(c: CountDeposit): Effect[KycAnswer] =
    val s = currentState
    if c.ordinal <= s.deposits.get(c.currency).fold(0)(_.lastOrdinal) then emit(Vector.empty)
    else
      val large = c.policy.currency.contains(c.currency) && c.policy.depositThreshold.exists(c.amount > _) && s.status != Status.Verified
      emit(DepositCounted(c.playerId, c.currency, c.amount, c.ordinal, c.at) +: flag(s, c.playerId, Option.when(large)(FlagReason.LargeDeposit), c.at).toVector)

  def profileChanged(c: ProfileChanged): Effect[KycAnswer] =
    val s = currentState
    if !c.requiredChanged && c.present == s.profile.present && c.adultFrom.map(_.reveal) == s.profile.adultFrom.map(_.reveal) then emit(Vector.empty)
    else
      emit(withDerivation(Vector(ProfileUpdated(c.present, c.adultFrom, c.requiredChanged, c.at)), Trigger.Profile, c.policyRequired, c.at))

  /**
   * The profile a player registered with, unless the record already knows one: `player_profile_updated` carries
   * every field the player holds, so a registration read after an edit adds nothing (R11).
   */
  def registeredProfile(c: ProfileChanged): Effect[KycAnswer] =
    if currentState.profile.present.nonEmpty then emit(Vector.empty) else profileChanged(c)

  /** The deadline at registration plus the window, once; a policy with no window sets none (FR-025). */
  def setDeadline(c: SetDeadline): Effect[KycAnswer] =
    (currentState.deadline, c.windowDays) match
      case (None, Some(days)) => emit(Vector(DeadlineSet(c.playerId, c.registeredAt.plus(Duration.ofDays(days.toLong)), false, c.at)))
      case _                  => emit(Vector.empty)

  /**
   * The window lapsed: nothing for a verified or closed player, another deadline, or one already handled;
   * otherwise the window flag, once, and whether to suspend (R10).
   */
  def windowLapsed(c: WindowLapsed): Effect[KycAnswer] =
    val s = currentState
    s.deadline match
      case Some(d) if d.at == c.deadline && !d.handled && !s.closed && s.status != Status.Verified =>
        emit(flag(s, s.playerId, Some(FlagReason.Window), c.at).toVector :+ DeadlineHandled(d.at, c.suspendOnExpiry && !d.migrated, c.at))
      case _ => emit(Vector.empty)

  def suspensionRequested(c: RequestedSuspension): Effect[KycAnswer] =
    currentState.deadline match
      case Some(d) if d.at == c.deadline && !d.suspensionRequested => emit(Vector(SuspensionRequested(d.at, c.at)))
      case _                                                     => emit(Vector.empty)

  /** A lowered threshold flags, once, a player already over it (FR-022). */
  def flagForPolicy(c: FlagForPolicy): Effect[KycAnswer] =
    val s        = currentState
    val lifetime = s.lifetime(c.policy.currency)
    val threshold = Option.when(s.status != Status.Verified && c.policy.depositThreshold.exists(lifetime > _))(FlagReason.Threshold)
    val sof       = Option.when(s.sof != Sof.Cleared && c.policy.sofThreshold.exists(lifetime > _))(FlagReason.SourceOfFunds)
    emit(Vector(threshold, sof).flatMap(flag(s, s.playerId, _, c.at)))

  def close(c: Close): Effect[KycAnswer] =
    if currentState.closed then emit(Vector.empty) else emit(Vector(Closed(c.at)))

  def open(c: Open): Effect[KycAnswer] =
    if !currentState.isEmpty then refuse("record_exists") else emit(Vector(Opened(c.record, c.at)))

  /** Every personal value blanked; ids, categories, times, statuses and reason codes stay (FR-033). Every call records it. */
  def erase(c: Erase): Effect[KycAnswer] = emit(Vector(Erased(c.at)))

  def get: ReadOnlyEffect[KycState] = effects.reply(currentState)

object KycRecord
    extends EventSourcedEntity.Companion[KycRecord, KycState, KycEvent](
      componentId = ComponentId("kyc-record"),
      stateSerializer = KycCodecs.serializer[KycState]("record-state"),
      eventSerializer = KycCodecs.serializer[KycEvent]("record-event")
    ):
  given Serializer[KycState]            = KycCodecs.serializer[KycState]("record-reply")
  given Serializer[KycAnswer]           = KycCodecs.serializer[KycAnswer]("record-answer")
  given Serializer[RequestUpload]       = KycCodecs.serializer[RequestUpload]("request-upload")
  given Serializer[ConfirmUpload]       = KycCodecs.serializer[ConfirmUpload]("confirm-upload")
  given Serializer[ReviewDocument]      = KycCodecs.serializer[ReviewDocument]("review-document")
  given Serializer[IssueView]           = KycCodecs.serializer[IssueView]("issue-view")
  given Serializer[SetMode]             = KycCodecs.serializer[SetMode]("set-mode")
  given Serializer[SetStatus]           = KycCodecs.serializer[SetStatus]("set-status")
  given Serializer[Override]            = KycCodecs.serializer[Override]("override")
  given Serializer[SetRequired]         = KycCodecs.serializer[SetRequired]("set-required")
  given Serializer[ClearSof]            = KycCodecs.serializer[ClearSof]("clear-sof")
  given Serializer[ResetSof]            = KycCodecs.serializer[ResetSof]("reset-sof")
  given Serializer[DismissFlag]         = KycCodecs.serializer[DismissFlag]("dismiss-flag")
  given Serializer[DepositCheck]        = KycCodecs.serializer[DepositCheck]("deposit-check")
  given Serializer[CountDeposit]        = KycCodecs.serializer[CountDeposit]("count-deposit")
  given Serializer[ProfileChanged]      = KycCodecs.serializer[ProfileChanged]("profile-changed")
  given Serializer[SetDeadline]         = KycCodecs.serializer[SetDeadline]("set-deadline")
  given Serializer[WindowLapsed]        = KycCodecs.serializer[WindowLapsed]("window-lapsed")
  given Serializer[RequestedSuspension] = KycCodecs.serializer[RequestedSuspension]("requested-suspension")
  given Serializer[FlagForPolicy]       = KycCodecs.serializer[FlagForPolicy]("flag-for-policy")
  given Serializer[Close]               = KycCodecs.serializer[Close]("close")
  given Serializer[Open]                = KycCodecs.serializer[Open]("open")
  given Serializer[Erase]               = KycCodecs.serializer[Erase]("erase")

  def create(context: EventSourcedEntityContext) = new KycRecord(context)

  val requestUpload       = command("request-upload")(_.requestUpload)
  val confirmUpload       = command("confirm-upload")(_.confirmUpload)
  val review              = command("review")(_.review)
  val issueView           = command("issue-view")(_.issueView)
  val setMode             = command("set-mode")(_.setMode)
  val setStatus           = command("set-status")(_.setStatus)
  val overrideStatus      = command("override")(_.overrideStatus)
  val setRequired         = command("set-required")(_.setRequired)
  val clearSof            = command("clear-sof")(_.clearSof)
  val resetSof            = command("reset-sof")(_.resetSof)
  val dismissFlag         = command("dismiss-flag")(_.dismissFlag)
  val depositCheck        = command("deposit-check")(_.depositCheck)
  val countDeposit        = command("count-deposit")(_.countDeposit)
  val profileChanged      = command("profile-changed")(_.profileChanged)
  val registeredProfile   = command("registered-profile")(_.registeredProfile)
  val setDeadline         = command("set-deadline")(_.setDeadline)
  val windowLapsed        = command("window-lapsed")(_.windowLapsed)
  val suspensionRequested = command("suspension-requested")(_.suspensionRequested)
  val flagForPolicy       = command("flag-for-policy")(_.flagForPolicy)
  val close               = command("close")(_.close)
  val open                = command("open")(_.open)
  val erase               = command("erase")(_.erase)
  val get                 = query("get")(_.get)

  def today(at: Instant): LocalDate = LocalDate.ofInstant(at, ZoneOffset.UTC)

  def isExpired(o: Offer, at: Instant): Boolean = o.offeredAt.plus(Offer.Lifetime).isBefore(at)

  /** The offers an hour old, dropped (R7). */
  def expired(s: KycState, at: Instant): Vector[KycEvent] =
    val ids = s.offers.values.filter(isExpired(_, at)).map(_.documentId).toVector.sorted
    if ids.isEmpty then Vector.empty else Vector(OffersDropped(ids, at))

  /** A flag of the reason, unless one is open (FR-024). */
  def flag(s: KycState, playerId: String, reason: Option[FlagReason], at: Instant): Option[KycEvent] =
    reason.filter(s.openFlag(_).isEmpty).map(Flagged(playerId, _, at))

  /**
   * The derivation's events on `s` (R4): a status change, then a ready time set or cleared. A record with
   * no document derives nothing, as the original's trigger fired only for a player with one
   * (`kyc_documents != ""`).
   */
  def derived(s: KycState, trigger: Trigger, policyRequired: Vector[Category], at: Instant): Vector[KycEvent] =
    if s.documents.isEmpty then Vector.empty
    else
      val d      = Rollup.derive(s.rollupInputs(policyRequired, today(at)), trigger)
      val status = d.status.map(n => StatusChanged(s.status, n, Actor.System, d.reason.fold("document")(_.wire), None, at))
      val ready  = d.ready.map(r => if r then ReadySet(at) else ReadyCleared(at))
      status.toVector ++ ready

  /** Verification closes the threshold, window and large deposit flags; a cleared source of funds its own (FR-024). */
  private def closing(flags: Vector[Flag], reasons: Set[FlagReason], at: Instant): Vector[Flag] =
    flags.map(f => if f.open && reasons(f.reason) then f.copy(closedAt = Some(at)) else f)

  private val ClosedByVerification: Set[FlagReason] = Set(FlagReason.Threshold, FlagReason.Window, FlagReason.LargeDeposit)

  def applied(s: KycState, event: KycEvent): KycState = event match
    case e: UploadOffered    => s.copy(playerId = e.playerId, offers = s.offers + (e.offer.documentId -> e.offer))
    case e: DocumentRecorded =>
      s.copy(playerId = e.playerId, documents = s.documents :+ e.document, offers = s.offers - e.document.id, documentsChangedAt = Some(e.at))
    case e: DocumentReviewed =>
      s.copy(documents = s.documents.map(d => if d.id == e.documentId then d.copy(review = Some(e.review)) else d), documentsChangedAt = Some(e.at))
    case _: ViewIssued      => s
    case e: ModeChanged     => s.copy(mode = e.mode)
    case e: ReadySet        => s.copy(readyAt = Some(e.at))
    case _: ReadyCleared    => s.copy(readyAt = None)
    case e: StatusChanged =>
      if e.status == Status.Verified then s.copy(status = e.status, verifiedAt = Some(e.at), flags = closing(s.flags, ClosedByVerification, e.at))
      else s.copy(status = e.status)
    case e: RequiredChanged => s.copy(required = e.categories)
    case e: SofCleared      => s.copy(sof = Sof.Cleared, flags = closing(s.flags, Set(FlagReason.SourceOfFunds), e.at))
    case _: SofReset        => s.copy(sof = Sof.Unverified)
    case e: Flagged         => s.copy(playerId = e.playerId, flags = s.flags :+ Flag(e.reason, e.at))
    case e: FlagDismissed =>
      val i = s.flags.indexWhere(f => f.reason == e.reason && f.open)
      if i < 0 then s else s.copy(flags = s.flags.updated(i, s.flags(i).copy(dismissed = Some(Dismissal(e.operatorId, e.note, e.at)))))
    case e: DepositCounted =>
      val d = s.deposits.getOrElse(e.currency, Deposits())
      s.copy(playerId = e.playerId, deposits = s.deposits + (e.currency -> Deposits(d.total + e.amount, e.ordinal)))
    case e: ProfileUpdated =>
      s.copy(profile = ProfileFacts(e.present, e.adultFrom, if e.requiredChanged then Some(e.at) else s.profile.changedAt))
    case e: DeadlineSet         => s.copy(playerId = e.playerId, deadline = Some(Deadline(e.deadline, e.migrated)))
    case e: DeadlineHandled     => s.copy(deadline = s.deadline.map(d => if d.at == e.deadline then d.copy(handled = true, suspend = e.suspend) else d))
    case e: SuspensionRequested => s.copy(deadline = s.deadline.map(d => if d.at == e.deadline then d.copy(suspensionRequested = true) else d))
    case e: Closed              => s.copy(closed = true, flags = closing(s.flags, FlagReason.values.toSet, e.at))
    case e: OffersDropped       => s.copy(offers = s.offers -- e.documentIds)
    case e: Opened              => e.record
    case _: Erased =>
      val blank = Personal("")
      s.copy(
        erased = true,
        documents = s.documents.map(d => d.copy(by = d.by.copy(reason = d.by.reason.map(_ => blank)))),
        offers = s.offers.map((k, o) => k -> o.copy(by = o.by.copy(reason = o.by.reason.map(_ => blank)))),
        profile = s.profile.copy(adultFrom = None),
        flags = s.flags.map(f => f.copy(dismissed = f.dismissed.map(_.copy(note = blank))))
      )
