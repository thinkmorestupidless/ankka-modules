package io.eitheror.casino.kyc.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.policy.{Policy, PolicyChanged, Profiles}
import io.eitheror.casino.kyc.record.*
import io.eitheror.casino.kyc.record.KycEvent.*

import java.time.Instant

/** Every event to its family or none, and the audit's records (contracts/facts.md, T015). */
class PublishersSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-30T12:00:00Z")
  private val op = Actor.operator("op-1")

  private def fact(e: KycEvent)  = Facts.players("p-1", 7, e).map(_.fact)
  private def audit(e: KycEvent) = Facts.audit("goldvibe", "p-1", 7, e).map(_.fact)

  private val document = Document("d-1", Category.IdentityFront, "subjects/p-1/kyc/identity_front-1.jpg", "image/jpeg", 10L, at)

  private def status(from: Status, to: Status, actor: Actor = Actor.System, reason: String = "document", note: Option[String] = None) =
    StatusChanged(from, to, actor, reason, note.map(Personal(_)), at)

  test("a document recorded is submitted; a review names the decision, the reason code and the reviewer"):
    assertEquals(fact(DocumentRecorded("p-1", document, at)), Some(f.PlayerKycSubmitted("p-1", "d-1", "identity_front", at)))
    val review = Review(Outcome.Rejected, Some(ReasonCode.AddressMismatch), "op-1", at)
    assertEquals(fact(DocumentReviewed("d-1", Category.ProofOfAddress, review, at)),
      Some(f.PlayerKycDocumentReviewed("p-1", "d-1", "proof_of_address", "rejected", Some("address_mismatch"), "op-1", at)))

  test("a status change to verified, rejected or pending, each to its family"):
    assertEquals(fact(status(Status.Pending, Status.Verified)), Some(f.PlayerKycVerified("p-1", "pending", "verified", "system", None, Some("document"), at)))
    assertEquals(fact(status(Status.Pending, Status.Rejected, op, "agent")),
      Some(f.PlayerKycRejected("p-1", "pending", "rejected", "operator", Some("op-1"), Some("agent"), at)))
    assertEquals(fact(status(Status.Unverified, Status.Pending, op, "required_category")),
      Some(f.PlayerKycPending("p-1", "unverified", "pending", at, Some("required_category"))))

  test("player_kyc_pending carries its reason"):
    for r <- Vector("agent", "required_category", "profile_changed", "document") do
      assertEquals(fact(status(Status.Unverified, Status.Pending, reason = r)).collect { case p: f.PlayerKycPending => p.reason }, Some(Some(r)), r)

  test("verified to unverified is player_kyc_rejected with profile_changed"):
    assertEquals(fact(status(Status.Verified, Status.Unverified, reason = "profile_changed")),
      Some(f.PlayerKycRejected("p-1", "verified", "unverified", "system", None, Some("profile_changed"), at)))
    assertEquals(fact(status(Status.Pending, Status.Unverified)), None)

  test("a free-text reason never goes on a fact"):
    val overridden = fact(status(Status.Pending, Status.Verified, op, "override", Some("checked by phone")))
    assertEquals(overridden, Some(f.PlayerKycVerified("p-1", "pending", "verified", "operator", Some("op-1"), None, at)))
    assert(!overridden.toString.contains("phone"))

  test("a flag and a cleared source of funds"):
    assertEquals(fact(Flagged("p-1", FlagReason.LargeDeposit, at)), Some(f.PlayerKycReviewRequired("p-1", "large_deposit", at)))
    assertEquals(fact(SofCleared("op-1", at)), Some(f.PlayerSofCleared("p-1", "op-1", at)))

  test("the other events publish nothing"):
    val none = Vector[KycEvent](UploadOffered("p-1", Offer("d", Category.Other, "image/png", "n", Uploader.Player, at), at),
      ViewIssued("d-1", op, at), ModeChanged(Mode.Manual, Mode.Automatic, "op-1", at), ReadySet(at), ReadyCleared(at),
      RequiredChanged(Vector.empty, Vector.empty, "op-1", at), SofReset("op-1", at), FlagDismissed(FlagReason.Window, "op-1", Personal("n"), at),
      DepositCounted("p-1", "EUR", 1L, 1, at), ProfileUpdated(Set.empty, None, true, at), DeadlineSet("p-1", at, false, at),
      DeadlineHandled(at, true, at), SuspensionRequested(at, at), Closed(at), OffersDropped(Vector("d"), at), Erased(at))
    none.foreach(e => assertEquals(fact(e), None, e.toString))

  test("the fact's id is the event's, so a redelivered event is the same fact"):
    assertEquals(Facts.players("p-1", 7, Flagged("p-1", FlagReason.Window, at)).map(_.id), Some("record/p-1/7"))

  // platform.audit

  test("one audit record per status change, naming a note and never its text"):
    val r = audit(status(Status.Pending, Status.Verified, op, "override", Some("checked by phone"))).get
    assertEquals((r.action, r.actorKind, r.actorId, r.subjectKind, r.subjectId, r.service), ("kyc.status_changed", "operator", "op-1", "player", "p-1", "kyc"))
    assertEquals(r.changes, Vector(f.AuditChange("status", Some("pending"), Some("verified")), f.AuditChange("reason", None, Some("override")),
      f.AuditChange("note", None, None)))
    assert(!r.toString.contains("phone"))
    assertEquals(audit(status(Status.Unverified, Status.Pending)).map(r => (r.actorKind, r.actorId, r.recordClass)), Some(("system", "kyc", "system")))

  test("one per review with its reason code, view, source of funds change, flag dismissal and operator upload"):
    val review = Review(Outcome.Rejected, Some(ReasonCode.DocumentUnclear), "op-1", at)
    assertEquals(audit(DocumentReviewed("d-1", Category.IdentityFront, review, at)).map(r => (r.action, r.reasonCode)), Some(("kyc.document_reviewed", Some("document_unclear"))))
    assertEquals(audit(ViewIssued("d-1", Actor.Player, at)).map(r => (r.action, r.actorKind)), Some(("kyc.view_issued", "player")))
    assertEquals(audit(SofCleared("op-1", at)).map(_.action), Some("kyc.sof_changed"))
    assertEquals(audit(SofReset("op-1", at)).map(_.action), Some("kyc.sof_changed"))
    val dismissed = audit(FlagDismissed(FlagReason.Threshold, "op-1", Personal("known customer"), at)).get
    assertEquals(dismissed.action, "kyc.flag_dismissed")
    assert(!dismissed.toString.contains("known customer"))
    val support = audit(DocumentRecorded("p-1", document.copy(by = Uploader.support("op-2", "posted in")), at)).get
    assertEquals((support.action, support.actorId), ("kyc.upload_by_operator", "op-2"))
    assert(!support.toString.contains("posted in"))
    assertEquals(audit(DocumentRecorded("p-1", document, at)), None)

  test("a mode change and a required change, each with the operator and the values before and after"):
    val mode = audit(ModeChanged(Mode.Manual, Mode.Automatic, "op-3", at)).get
    assertEquals((mode.action, mode.actorKind, mode.actorId, mode.changes),
      ("kyc.mode_changed", "operator", "op-3", Vector(f.AuditChange("mode", Some("automatic"), Some("manual")))))
    val required = audit(RequiredChanged(Vector(Category.IdentityFront, Category.ProofOfAddress), Vector.empty, "op-3", at)).get
    assertEquals((required.action, required.actorId, required.changes),
      ("kyc.required_changed", "op-3", Vector(f.AuditChange("categories", None, Some("identity_front,proof_of_address")))))

  test("a policy change names every field that changed, before and after, the brand its subject"):
    val r = Facts.policyAudit("goldvibe", 3, PolicyChanged(Policy.Default, Profiles.Ukgc, "op-1", at)).fact
    assertEquals((r.action, r.subjectKind, r.subjectId, r.actorId), ("kyc.policy_changed", "brand", "goldvibe", "op-1"))
    assertEquals(r.changes.map(_.field), Vector("depositGate", "playGate", "currency", "sofThreshold", "windowDays", "required", "profile"))
    assertEquals(r.changes.find(_.field == "currency"), Some(f.AuditChange("currency", None, Some("GBP"))))
