package io.eitheror.casino.kyc.record

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.KycEvent.*

import java.time.{Duration, Instant, LocalDate}

/** data-model.md's command table for `KycRecord`, row by row and in its order (T013). */
class KycRecordSuite extends munit.FunSuite with LogCapturing:
  private val at                = Instant.parse("2026-10-30T12:00:00Z")
  private def later(m: Long)    = at.plus(Duration.ofMinutes(m))
  private val identity          = Vector(Category.Identity)
  private val player            = "p-1"
  private def fresh             = EventSourcedTestKit.of(KycRecord, player)
  private def code(a: KycAnswer) = a.refusal.map(_.code)
  private def names(events: Seq[KycEvent]) = events.map(_.getClass.getSimpleName)
  private def ev(events: KycEvent*): Vector[KycEvent] = events.toVector

  private type Kit = EventSourcedTestKit[KycRecord, KycState, KycEvent]

  private def offer(kit: Kit, category: Category, id: String, contentType: String = "image/jpeg", when: Instant = at) =
    kit.call(KycRecord.requestUpload)(RequestUpload(player, category.wire, contentType, Uploader.Player, id, when))

  private def upload(kit: Kit, category: Category, id: String, when: Instant = at) =
    offer(kit, category, id, when = when): Unit
    kit.call(KycRecord.confirmUpload)(ConfirmUpload(id, 100L, "image/jpeg", identity, when))

  private def review(kit: Kit, id: String, outcome: Outcome, reason: Option[String] = None, when: Instant = at) =
    kit.call(KycRecord.review)(ReviewDocument(id, outcome, reason, "op-1", identity, when))

  private val adult = Some(Personal(LocalDate.parse("2008-01-15")))

  private def completeProfile(kit: Kit, requiredChanged: Boolean = false, when: Instant = at) =
    kit.call(KycRecord.profileChanged)(ProfileChanged(player, ProfileField.values.toSet, adult, requiredChanged, identity, when))

  private def verified(kit: Kit) =
    completeProfile(kit): Unit
    upload(kit, Category.IdentityFront, "d-1"): Unit
    review(kit, "d-1", Outcome.Approved): Unit
    assertEquals(kit.currentState.status, Status.Verified)

  // RequestUpload

  test("RequestUpload: refused for a closed or erased account, an unknown or legacy category, a type not accepted"):
    val kit = fresh
    assertEquals(code(offer(kit, Category.Other, "d-1", "image/gif").replyValue), Some("file_rejected"))
    assertEquals(code(kit.call(KycRecord.requestUpload)(RequestUpload(player, "selfie", "image/jpeg", Uploader.Player, "d-1", at)).replyValue),
      Some("invalid_category"))
    assertEquals(code(offer(kit, Category.Identity, "d-1").replyValue), Some("invalid_category"))
    kit.call(KycRecord.close)(Close(at)): Unit
    assertEquals(code(offer(kit, Category.Other, "d-1").replyValue), Some("account_closed"))
    val erased = fresh
    erased.call(KycRecord.erase)(Erase(at)): Unit
    assertEquals(code(offer(erased, Category.Other, "d-1").replyValue), Some("account_closed"))

  test("RequestUpload: offered with the object name, by the player or support"):
    val kit    = fresh
    val result = offer(kit, Category.ProofOfAddress, "d-1", "application/pdf")
    assertEquals(result.events, ev(UploadOffered(player, Offer("d-1", Category.ProofOfAddress, "application/pdf",
      s"subjects/p-1/kyc/proof_of_address-${at.toEpochMilli}.pdf", Uploader.Player, at), at)))
    val support = kit.call(KycRecord.requestUpload)(RequestUpload(player, "other", "image/png", Uploader.support("op-1", "posted"), "d-2", at))
    assert(support.replyValue.state.offers("d-2").by.bySupport)

  test("RequestUpload: a category whose latest is pending or approved is locked; rejected or none is not; other never is (FR-006)"):
    val kit = fresh
    upload(kit, Category.IdentityFront, "d-1"): Unit
    assertEquals(code(offer(kit, Category.IdentityFront, "d-2").replyValue), Some("document_locked"))
    review(kit, "d-1", Outcome.Approved): Unit
    assertEquals(code(offer(kit, Category.IdentityFront, "d-2").replyValue), Some("document_locked"))
    upload(kit, Category.ProofOfAddress, "d-3"): Unit
    review(kit, "d-3", Outcome.Rejected, Some("document_unclear")): Unit
    assertEquals(code(offer(kit, Category.ProofOfAddress, "d-4").replyValue), None)
    upload(kit, Category.Other, "d-5"): Unit
    assertEquals(code(offer(kit, Category.Other, "d-6").replyValue), None)

  // ConfirmUpload

  test("ConfirmUpload: an id never offered is not found; the content type must be the one offered"):
    val kit = fresh
    assertEquals(code(kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-9", 1L, "image/jpeg", identity, at)).replyValue), Some("document_not_found"))
    offer(kit, Category.Other, "d-1"): Unit
    val wrong = kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 1L, "image/png", identity, at)).replyValue
    assertEquals(wrong.refusal, Some(Refusal("file_rejected", Vector("contentType"))))

  test("ConfirmUpload: records the document as pending and derives: unverified becomes pending, for a document"):
    val kit    = fresh
    offer(kit, Category.IdentityFront, "d-1"): Unit
    val result = kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 100L, "image/jpeg", identity, later(5)))
    assertEquals(names(result.events), Seq("DocumentRecorded", "StatusChanged"))
    assertEquals(result.events.last, StatusChanged(Status.Unverified, Status.Pending, Actor.System, "document", None, later(5)))
    val doc = kit.currentState.documents.head
    assertEquals((doc.uploadedAt, doc.awaiting, doc.size), (later(5), true, 100L))
    assertEquals(kit.currentState.offers, Map.empty)

  test("ConfirmUpload: a retried confirm writes nothing and answers; a second offer of a locked category is refused at confirm"):
    val kit = fresh
    offer(kit, Category.IdentityFront, "d-1"): Unit
    offer(kit, Category.IdentityFront, "d-2"): Unit
    kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 100L, "image/jpeg", identity, at)): Unit
    val again = kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 0L, "", identity, later(1)))
    assertEquals((again.events, code(again.replyValue)), (ev(), None))
    assertEquals(code(kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-2", 100L, "image/jpeg", identity, at)).replyValue),
      Some("document_locked"))
    assertEquals(kit.currentState.documents.map(_.id), Vector("d-1"))

  test("offers are dropped an hour after they are offered: the confirm is refused upload_expired, a later one not found"):
    val kit = fresh
    offer(kit, Category.Other, "d-1"): Unit
    val late = kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 1L, "image/jpeg", identity, later(61)))
    assertEquals(code(late.replyValue), Some("upload_expired"))
    assertEquals(late.events, ev(OffersDropped(Vector("d-1"), later(61))))
    assertEquals(code(kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 1L, "image/jpeg", identity, later(62))).replyValue),
      Some("document_not_found"))

  test("an offer an hour old is dropped by the next command that records anything"):
    val kit = fresh
    offer(kit, Category.Other, "d-1"): Unit
    val next = offer(kit, Category.Other, "d-2", when = later(61))
    assertEquals(names(next.events), Seq("UploadOffered", "OffersDropped"))
    assertEquals(kit.currentState.offers.keySet, Set("d-2"))

  // Review

  test("Review: the reason code is checked first, then the document, then whether it was reviewed"):
    val kit = fresh
    assertEquals(code(review(kit, "d-9", Outcome.Rejected).replyValue), Some("invalid_rejection_reason"))
    assertEquals(code(review(kit, "d-9", Outcome.Rejected, Some("blurry")).replyValue), Some("invalid_rejection_reason"))
    assertEquals(code(review(kit, "d-9", Outcome.Approved).replyValue), Some("document_not_found"))
    upload(kit, Category.IdentityFront, "d-1"): Unit
    review(kit, "d-1", Outcome.Rejected, Some("document_expired")): Unit
    assertEquals(code(review(kit, "d-1", Outcome.Approved).replyValue), Some("document_already_reviewed"))
    assertEquals(kit.currentState.status, Status.Rejected)
    assertEquals(kit.currentState.documents.head.review.flatMap(_.reasonCode), Some(ReasonCode.DocumentExpired))

  test("Review: an approval verifies a complete profile, by the system, and closes the flags verification closes"):
    val kit = fresh
    completeProfile(kit): Unit
    kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 300000L, at)): Unit
    upload(kit, Category.IdentityFront, "d-1"): Unit
    val result = review(kit, "d-1", Outcome.Approved, when = later(1))
    assertEquals(result.events.last, StatusChanged(Status.Pending, Status.Verified, Actor.System, "document", None, later(1)))
    assertEquals(kit.currentState.verifiedAt, Some(later(1)))
    assertEquals(kit.currentState.openFlag(FlagReason.Threshold), None)

  test("Review: an approval with the profile incomplete leaves the account pending and writes no status (FR-014)"):
    val kit = fresh
    upload(kit, Category.IdentityFront, "d-1"): Unit
    assertEquals(names(review(kit, "d-1", Outcome.Approved).events), Seq("DocumentReviewed"))
    assertEquals(kit.currentState.missingProfileFields(LocalDate.parse("2026-10-30")).size, 8)

  // IssueView

  test("IssueView: an unknown document is not found; after erasure, erased; otherwise recorded every time"):
    val kit = fresh
    assertEquals(code(kit.call(KycRecord.issueView)(IssueView("d-1", Actor.Player, at)).replyValue), Some("document_not_found"))
    upload(kit, Category.Other, "d-1"): Unit
    assertEquals(kit.call(KycRecord.issueView)(IssueView("d-1", Actor.operator("op-1"), at)).events, ev(ViewIssued("d-1", Actor.operator("op-1"), at)))
    assertEquals(kit.call(KycRecord.issueView)(IssueView("d-1", Actor.Player, at)).events.size, 1)
    kit.call(KycRecord.erase)(Erase(at)): Unit
    assertEquals(code(kit.call(KycRecord.issueView)(IssueView("d-1", Actor.Player, at)).replyValue), Some("document_erased"))

  // SetMode, SetStatus

  test("SetMode: manual stops the derivation; automatic clears the ready time and derives at once (FR-015)"):
    val kit = fresh
    completeProfile(kit): Unit
    assertEquals(names(kit.call(KycRecord.setMode)(SetMode(Mode.Manual, "op-1", identity, at)).events), Seq("ModeChanged"))
    // Nothing is required of the player, so the first document makes the account vacuously ready (FR-018).
    assertEquals(names(upload(kit, Category.IdentityFront, "d-1").events), Seq("DocumentRecorded", "ReadySet"))
    assertEquals(names(review(kit, "d-1", Outcome.Approved).events), Seq("DocumentReviewed"))
    assertEquals(kit.currentState.status, Status.Unverified)
    val auto = kit.call(KycRecord.setMode)(SetMode(Mode.Automatic, "op-1", identity, later(1)))
    assertEquals(names(auto.events), Seq("ModeChanged", "ReadyCleared", "StatusChanged"))
    assertEquals((kit.currentState.status, kit.currentState.readyAt), (Status.Verified, None))
    assertEquals(kit.call(KycRecord.setMode)(SetMode(Mode.Automatic, "op-1", identity, later(2))).events, ev())

  test("SetStatus: verified refused while the profile is incomplete, naming the fields; otherwise by the operator, as an agent"):
    val kit = fresh
    val refused = kit.call(KycRecord.setStatus)(SetStatus(Status.Verified, "op-1", identity, at)).replyValue
    assertEquals(refused.refusal.map(r => (r.code, r.fields.head)), Some(("profile_incomplete", "firstName")))
    val pending = kit.call(KycRecord.setStatus)(SetStatus(Status.Pending, "op-1", identity, at))
    assertEquals(pending.events, ev(StatusChanged(Status.Unverified, Status.Pending, Actor.operator("op-1"), "agent", None, at)))
    completeProfile(kit): Unit
    assertEquals(kit.call(KycRecord.setStatus)(SetStatus(Status.Verified, "op-1", identity, at)).replyValue.state.status, Status.Verified)

  // SetRequired

  test("SetRequired: an unknown category refused; a non-empty list puts the player in manual mode and pending (FR-017)"):
    val kit = fresh
    assertEquals(kit.call(KycRecord.setRequired)(SetRequired(Vector("selfie"), "op-1", identity, at)).replyValue.refusal,
      Some(Refusal("invalid_category", Vector("selfie"))))
    val set = kit.call(KycRecord.setRequired)(SetRequired(Vector("proof_of_address"), "op-1", identity, at))
    assertEquals(names(set.events), Seq("RequiredChanged", "ModeChanged", "StatusChanged"))
    assertEquals(set.events.last, StatusChanged(Status.Unverified, Status.Pending, Actor.operator("op-1"), "required_category", None, at))
    assertEquals((kit.currentState.mode, kit.currentState.required), (Mode.Manual, Vector(Category.ProofOfAddress)))

  test("SetRequired: a rejected account stays rejected; the ready time follows the player's categories"):
    val kit = fresh
    completeProfile(kit): Unit
    upload(kit, Category.ProofOfAddress, "d-1"): Unit
    review(kit, "d-1", Outcome.Rejected, Some("address_mismatch")): Unit
    val set = kit.call(KycRecord.setRequired)(SetRequired(Vector("identity_front"), "op-1", identity, at))
    assertEquals(names(set.events), Seq("RequiredChanged", "ModeChanged"))
    upload(kit, Category.IdentityFront, "d-2"): Unit
    assertEquals(names(review(kit, "d-2", Outcome.Approved).events), Seq("DocumentReviewed", "ReadySet"))
    assertEquals(kit.currentState.status, Status.Rejected)

  // Override, source of funds, flags

  test("Override: verified or rejected, regardless of the documents, by the operator with a personal reason"):
    val kit    = fresh
    val result = kit.call(KycRecord.overrideStatus)(Override(Status.Verified, Some("checked by phone"), "op-1", identity, at))
    assertEquals(result.events, ev(StatusChanged(Status.Unverified, Status.Verified, Actor.operator("op-1"), "override", Some(Personal("checked by phone")), at)))
    assertEquals(kit.call(KycRecord.overrideStatus)(Override(Status.Verified, None, "op-1", identity, at)).events, ev())

  test("ClearSof and ResetSof: once each, and a cleared source of funds closes its flag"):
    val kit = fresh
    kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Ukgc.copy(depositGate = io.eitheror.casino.kyc.policy.Gate.Off), Some("GBP"), 300000L, at)): Unit
    assert(kit.currentState.openFlag(FlagReason.SourceOfFunds).isDefined)
    assertEquals(kit.call(KycRecord.resetSof)(ResetSof("op-1", at)).events, ev())
    assertEquals(kit.call(KycRecord.clearSof)(ClearSof("op-1", at)).events, ev(SofCleared("op-1", at)))
    assertEquals(kit.currentState.openFlag(FlagReason.SourceOfFunds), None)
    assertEquals(kit.call(KycRecord.clearSof)(ClearSof("op-1", at)).events, ev())
    assertEquals(kit.call(KycRecord.resetSof)(ResetSof("op-1", at)).events, ev(SofReset("op-1", at)))

  test("DismissFlag: no open flag of the reason is not found; a note is required; dismissed with it"):
    val kit = fresh
    assertEquals(code(kit.call(KycRecord.dismissFlag)(DismissFlag("threshold", "ok", "op-1", at)).replyValue), Some("flag_not_found"))
    kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 300000L, at)): Unit
    assertEquals(code(kit.call(KycRecord.dismissFlag)(DismissFlag("threshold", " ", "op-1", at)).replyValue), Some("note_required"))
    assertEquals(code(kit.call(KycRecord.dismissFlag)(DismissFlag("nonsense", "ok", "op-1", at)).replyValue), Some("flag_not_found"))
    val dismissed = kit.call(KycRecord.dismissFlag)(DismissFlag("threshold", "known customer", "op-1", at))
    assertEquals(dismissed.events, ev(FlagDismissed(FlagReason.Threshold, "op-1", Personal("known customer"), at)))
    assertEquals(kit.currentState.openFlag(FlagReason.Threshold), None)

  // Deposits

  test("DepositCheck: answers, and raises its flag once while it is open"):
    val kit   = fresh
    val first = kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 300000L, at))
    assertEquals(first.replyValue.gate.flatMap(_.code), Some("DEPOSIT_BLOCKED_KYC_THRESHOLD"))
    assertEquals(first.events, ev(Flagged(player, FlagReason.Threshold, at)))
    assertEquals(kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 300000L, at)).events, ev())
    assertEquals(kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 100L, at)).replyValue.gate, Some(Gates.Allowed))

  test("CountDeposit: counted once per ordinal, per currency; the gate reads the KYC currency's total"):
    val kit = fresh
    kit.call(KycRecord.countDeposit)(CountDeposit(player, "EUR", 150000L, 1, Profiles.Mga, at)): Unit
    assertEquals(kit.call(KycRecord.countDeposit)(CountDeposit(player, "EUR", 150000L, 1, Profiles.Mga, at)).events, ev())
    kit.call(KycRecord.countDeposit)(CountDeposit(player, "GBP", 900000L, 1, Profiles.Mga, at)): Unit
    assertEquals(kit.currentState.deposits, Map("EUR" -> Deposits(150000L, 1), "GBP" -> Deposits(900000L, 1)))
    val gate = kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 50001L, at)).replyValue.gate
    assertEquals(gate.flatMap(_.code), Some("DEPOSIT_BLOCKED_KYC_THRESHOLD"))

  test("CountDeposit: one deposit over the threshold flags a player not verified, never a verified one (FR-038)"):
    val kit = fresh
    val large = kit.call(KycRecord.countDeposit)(CountDeposit(player, "EUR", 250000L, 1, Profiles.Mga, at))
    assertEquals(names(large.events), Seq("DepositCounted", "Flagged"))
    assertEquals(kit.currentState.openFlag(FlagReason.LargeDeposit).map(_.reason), Some(FlagReason.LargeDeposit))
    assertEquals(names(kit.call(KycRecord.countDeposit)(CountDeposit(player, "GBP", 250000L, 1, Profiles.Mga, at)).events), Seq("DepositCounted"))
    val v = fresh
    verified(v)
    assertEquals(names(v.call(KycRecord.countDeposit)(CountDeposit(player, "EUR", 250000L, 1, Profiles.Mga, at)).events), Seq("DepositCounted"))

  // Profile

  test("ProfileChanged: completing the profile verifies approved documents; nothing new writes nothing"):
    val kit = fresh
    upload(kit, Category.IdentityFront, "d-1"): Unit
    review(kit, "d-1", Outcome.Approved): Unit
    val complete = completeProfile(kit, requiredChanged = true, when = later(1))
    assertEquals(complete.events.last, StatusChanged(Status.Pending, Status.Verified, Actor.System, "profile_changed", None, later(1)))
    assertEquals(completeProfile(kit, when = later(2)).events, ev())

  test("ProfileChanged: a required field changed on a verified account returns it to unverified (FR-016)"):
    val kit = fresh
    verified(kit)
    val changed = completeProfile(kit, requiredChanged = true, when = later(10))
    assertEquals(changed.events.last, StatusChanged(Status.Verified, Status.Unverified, Actor.System, "profile_changed", None, later(10)))
    assertEquals(kit.currentState.documents.head.review.map(_.outcome), Some(Outcome.Approved))

  test("ProfileChanged: a change read again, or read after a later verification, leaves the account verified"):
    val kit = fresh
    completeProfile(kit, requiredChanged = true, when = later(1)): Unit
    upload(kit, Category.IdentityFront, "d-1", later(5)): Unit
    review(kit, "d-1", Outcome.Approved, when = later(6)): Unit
    assertEquals(kit.currentState.status, Status.Verified)
    assertEquals(completeProfile(kit, requiredChanged = true, when = later(1)).events, ev())
    val late = fresh
    verified(late)
    completeProfile(late, requiredChanged = true, when = later(-10)): Unit
    assertEquals(late.currentState.status, Status.Verified)
    completeProfile(late, requiredChanged = true, when = later(-20)): Unit
    assertEquals(late.currentState.profile.changedAt, Some(later(-10)))

  test("ProfileChanged: a change read late is recorded at its own time, and the status it causes at the reading"):
    val kit = fresh
    verified(kit)
    val read = kit.call(KycRecord.profileChanged)(
      ProfileChanged(player, ProfileField.values.toSet, adult, true, identity, later(30), Some(later(5))))
    assertEquals(read.events.last, StatusChanged(Status.Verified, Status.Unverified, Actor.System, "profile_changed", None, later(30)))
    assertEquals(kit.currentState.profile.changedAt, Some(later(5)))

  // The window

  test("SetDeadline: registration plus the window, once; a policy with no window sets none"):
    val kit = fresh
    assertEquals(kit.call(KycRecord.setDeadline)(SetDeadline(player, at, None, at)).events, ev())
    val set = kit.call(KycRecord.setDeadline)(SetDeadline(player, at, Some(30), at))
    assertEquals(set.events, ev(DeadlineSet(player, at.plus(Duration.ofDays(30)), false, at)))
    assertEquals(kit.call(KycRecord.setDeadline)(SetDeadline(player, at, Some(3), at)).events, ev())

  test("WindowLapsed: flags and decides to suspend, once; another deadline, or a verified player, does nothing"):
    val kit      = fresh
    val deadline = at.plus(Duration.ofDays(3))
    kit.call(KycRecord.setDeadline)(SetDeadline(player, at, Some(3), at)): Unit
    assertEquals(kit.call(KycRecord.windowLapsed)(WindowLapsed(at, true, deadline)).events, ev())
    val lapse = kit.call(KycRecord.windowLapsed)(WindowLapsed(deadline, true, deadline))
    assertEquals(lapse.events, ev(Flagged(player, FlagReason.Window, deadline), DeadlineHandled(deadline, true, deadline)))
    assertEquals(kit.call(KycRecord.windowLapsed)(WindowLapsed(deadline, true, deadline)).events, ev())
    assertEquals(kit.call(KycRecord.suspensionRequested)(RequestedSuspension(deadline, deadline)).events, ev(SuspensionRequested(deadline, deadline)))
    assertEquals(kit.call(KycRecord.suspensionRequested)(RequestedSuspension(deadline, deadline)).events, ev())
    val v = fresh
    verified(v)
    v.call(KycRecord.setDeadline)(SetDeadline(player, at, Some(3), at)): Unit
    assertEquals(v.call(KycRecord.windowLapsed)(WindowLapsed(deadline, true, deadline)).events, ev())

  test("WindowLapsed: a migrated deadline flags and never suspends (FR-025)"):
    val kit      = fresh
    val deadline = at.minus(Duration.ofDays(1))
    kit.call(KycRecord.open)(Open(KycState(player, deadline = Some(Deadline(deadline, migrated = true))), at)): Unit
    val lapse = kit.call(KycRecord.windowLapsed)(WindowLapsed(deadline, true, at))
    assertEquals(lapse.events.last, DeadlineHandled(deadline, false, at))

  test("FlagForPolicy: a lowered threshold flags a player already over it, once each (FR-022)"):
    val kit = fresh
    kit.call(KycRecord.countDeposit)(CountDeposit(player, "EUR", 150000L, 1, Profiles.Curacao, at)): Unit
    val lowered = Profiles.Mga.copy(depositThreshold = Some(100000L), sofThreshold = Some(120000L))
    assertEquals(kit.call(KycRecord.flagForPolicy)(FlagForPolicy(lowered, at)).events,
      ev(Flagged(player, FlagReason.Threshold, at), Flagged(player, FlagReason.SourceOfFunds, at)))
    assertEquals(kit.call(KycRecord.flagForPolicy)(FlagForPolicy(lowered, at)).events, ev())

  // Closure, migration, erasure

  test("Close: closed once, and its flags closed"):
    val kit = fresh
    kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 300000L, at)): Unit
    assertEquals(kit.call(KycRecord.close)(Close(at)).events, ev(Closed(at)))
    assertEquals(kit.currentState.flags.filter(_.open), Vector.empty)
    assertEquals(kit.call(KycRecord.close)(Close(at)).events, ev())

  test("Open: the migration opens only an empty record"):
    val kit = fresh
    upload(kit, Category.Other, "d-1"): Unit
    assertEquals(code(kit.call(KycRecord.open)(Open(KycState(player), at)).replyValue), Some("record_exists"))

  test("Erase: every personal value blanked; ids, categories, times, statuses and reason codes kept"):
    val kit = fresh
    completeProfile(kit): Unit
    kit.call(KycRecord.requestUpload)(RequestUpload(player, "identity_front", "image/jpeg", Uploader.support("op-1", "posted in"), "d-1", at)): Unit
    kit.call(KycRecord.confirmUpload)(ConfirmUpload("d-1", 10L, "image/jpeg", identity, at)): Unit
    review(kit, "d-1", Outcome.Rejected, Some("invalid_document")): Unit
    kit.call(KycRecord.depositCheck)(DepositCheck(player, Profiles.Mga, Some("EUR"), 300000L, at)): Unit
    kit.call(KycRecord.dismissFlag)(DismissFlag("threshold", "a note", "op-1", at)): Unit
    val before = kit.currentState
    kit.call(KycRecord.erase)(Erase(later(1))): Unit
    val after = kit.currentState
    assert(after.erased)
    assertEquals(after.profile.adultFrom, None)
    assertEquals(after.documents.head.by.reason.map(_.reveal), Some(""))
    assertEquals(after.flags.head.dismissed.map(_.note.reveal), Some(""))
    val kept = (d: Document) => (d.id, d.category, d.uploadedAt, d.review.map(r => (r.outcome, r.reasonCode, r.operatorId, r.at)), d.by.operatorId)
    assertEquals(after.documents.map(kept), before.documents.map(kept))
    assertEquals((after.status, after.flags.map(_.reason)), (before.status, before.flags.map(_.reason)))
    assertEquals(kit.call(KycRecord.erase)(Erase(later(2))).events, ev(Erased(later(2))))
