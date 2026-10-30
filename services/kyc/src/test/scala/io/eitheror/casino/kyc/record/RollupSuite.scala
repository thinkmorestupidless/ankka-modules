package io.eitheror.casino.kyc.record

import io.eitheror.casino.kyc.record.Category.*
import io.eitheror.casino.kyc.record.Rollup.{Derived, Doc, Inputs, Reason, Trigger}

import java.time.{Duration, Instant}

/**
 * The original's `rollup_test.go` and `handler_test.go` as rows, each named after the Go test it ports,
 * with the same inputs and outcomes (SC-001). Where the original rewrote an unchanged status, the row
 * expects nothing: only a change is written here (FR-014). The freshness of the original's timestamps is
 * the trigger: a fresh `kyc_documents_updated_at` is `Trigger.Documents`, a fresh `kyc_profile_updated_at`
 * alone is `Trigger.Profile`, and neither is no derivation at all. The profile rows of `rollup_test.go`
 * are `CompletenessSuite`'s; the cases on the wire's parsing apply to no typed state (research, Findings).
 */
class RollupSuite extends munit.FunSuite:
  private val now                        = Instant.parse("2026-10-05T12:00:00Z")
  private def ago(d: Duration): Instant  = now.minus(d)
  private def at(text: String): Instant  = Instant.parse(text)
  private def hours(n: Long)             = Duration.ofHours(n)
  private def minutes(n: Long)           = Duration.ofMinutes(n)

  private def approved(c: Category, uploaded: String) = Doc(c, at(uploaded), Some(Outcome.Approved))
  private def rejected(c: Category, uploaded: String) = Doc(c, at(uploaded), Some(Outcome.Rejected))
  private def pending(c: Category, uploaded: String)  = Doc(c, at(uploaded), None)

  private def status(docs: Doc*)(required: Category*): Status = Rollup.status(docs.toVector, required.toVector)

  // rollup_test.go

  test("LatestPerDocTypePicksLatestUpload"):
    val docs = Vector(rejected(Identity, "2026-08-20T10:00:00Z"), approved(Identity, "2026-08-22T10:00:00Z"))
    assertEquals(Rollup.latest(docs)(Identity).outcome, Some(Outcome.Approved))
    assertEquals(Rollup.status(docs, Vector(Identity)), Status.Verified)

  test("MultipleDocsPerType"):
    val docs = Vector(
      rejected(Identity, "2026-08-01T10:00:00Z"),
      rejected(Identity, "2026-08-10T10:00:00Z"),
      approved(Identity, "2026-08-20T10:00:00Z")
    )
    val latest = Rollup.latest(docs)
    assertEquals(latest.size, 1)
    assertEquals(latest(Identity).uploadedAt, at("2026-08-20T10:00:00Z"))
    assertEquals(Rollup.status(docs, Vector(Identity)), Status.Verified)

  test("AllRequiredTypesSatisfied"):
    assertEquals(
      status(approved(Identity, "2026-08-20T10:00:00Z"), approved(ProofOfAddress, "2026-08-21T10:00:00Z"))(Identity, ProofOfAddress),
      Status.Verified
    )

  test("OneRequiredTypeMissing"):
    assertEquals(status(approved(Identity, "2026-08-20T10:00:00Z"))(Identity, ProofOfAddress), Status.Pending)

  test("OneRequiredTypePending"):
    assertEquals(
      status(approved(Identity, "2026-08-20T10:00:00Z"), pending(ProofOfAddress, "2026-08-21T10:00:00Z"))(Identity, ProofOfAddress),
      Status.Pending
    )

  test("NoDocsReturnsUnverified"):
    assertEquals(status()(Identity), Status.Unverified)

  test("EmptyRequiredTypesNeverAutoVerifies"):
    assertEquals(status(approved(Identity, "2026-08-20T10:00:00Z"), approved(ProofOfAddress, "2026-08-21T10:00:00Z"))(), Status.Pending)

  test("AnyRejectedLatestDocWinsOverVerification"):
    assertEquals(
      status(approved(Identity, "2026-08-20T10:00:00Z"), rejected(ProofOfAddress, "2026-08-21T10:00:00Z"))(Identity, ProofOfAddress),
      Status.Rejected
    )

  // The original's row rejects a "selfie", which is no category here; a category not required stands in.
  test("AnyDocTypeRejectionForcesRejected"):
    assertEquals(status(approved(Identity, "2026-08-20T10:00:00Z"), rejected(PaymentMethod, "2026-08-21T10:00:00Z"))(Identity), Status.Rejected)

  test("LegacyIdentityDocSatisfiesIdentityFrontRequirement"):
    assertEquals(status(approved(Identity, "2026-09-01T10:00:00Z"))(IdentityFront), Status.Verified)

  test("IdentityFrontDocSatisfiesIdentityFrontRequirement"):
    assertEquals(status(approved(IdentityFront, "2026-09-01T10:00:00Z"))(IdentityFront), Status.Verified)

  test("PendingNonRequiredDocBlocksVerification"):
    assertEquals(status(approved(IdentityFront, "2026-09-01T10:00:00Z"), pending(IdentityBack, "2026-09-02T10:00:00Z"))(IdentityFront), Status.Pending)

  test("OtherDocPendingBlocksVerification"):
    assertEquals(status(approved(IdentityFront, "2026-09-01T10:00:00Z"), pending(Other, "2026-09-03T10:00:00Z"))(IdentityFront), Status.Pending)

  test("OtherDocApprovedDoesNotBlockVerification"):
    assertEquals(
      status(approved(IdentityFront, "2026-09-01T10:00:00Z"), approved(IdentityBack, "2026-09-02T10:00:00Z"), approved(Other, "2026-09-03T10:00:00Z"))(
        IdentityFront
      ),
      Status.Verified
    )

  test("IdentityFrontAndBackAreIndependent"):
    assertEquals(status(approved(IdentityFront, "2026-09-01T10:00:00Z"), rejected(IdentityBack, "2026-09-02T10:00:00Z"))(IdentityFront), Status.Rejected)
    assertEquals(status(approved(IdentityFront, "2026-09-01T10:00:00Z"))(IdentityFront), Status.Verified)

  test("IdentityRequirementMetByApprovedFront"):
    val front = Vector(approved(IdentityFront, "2026-09-01T10:00:00Z"))
    assertEquals(Rollup.status(front, Vector(Identity)), Status.Verified)
    assert(Rollup.readyForFinalReview(front, Vector(Identity)))
    assertEquals(status(approved(IdentityBack, "2026-09-01T10:00:00Z"))(Identity), Status.Pending)

  test("IdentityRequirementMetByLegacyIdentity"):
    assertEquals(status(approved(Identity, "2026-08-01T10:00:00Z"))(Identity), Status.Verified)

  test("FrontSupersedesLegacyIdentity"):
    assertEquals(status(rejected(Identity, "2026-08-01T10:00:00Z"), approved(IdentityFront, "2026-09-01T10:00:00Z"))(Identity), Status.Verified)
    assertEquals(status(approved(Identity, "2026-08-01T10:00:00Z"), pending(IdentityFront, "2026-09-01T10:00:00Z"))(Identity), Status.Pending)

  test("EveryOtherDocCountsForPending"):
    assertEquals(
      status(approved(IdentityFront, "2026-09-01T10:00:00Z"), pending(Other, "2026-09-02T10:00:00Z"), approved(Other, "2026-09-03T10:00:00Z"))(Identity),
      Status.Pending
    )

  test("RejectedOtherDocDoesNotRejectAccount"):
    assertEquals(status(approved(IdentityFront, "2026-09-01T10:00:00Z"), rejected(Other, "2026-09-02T10:00:00Z"))(Identity), Status.Verified)

  test("ComputeReadyForFinalReviewLegacyIdentity"):
    assert(Rollup.readyForFinalReview(Vector(approved(Identity, "2026-09-01T10:00:00Z")), Vector(IdentityFront)))

  test("ComputeReadyForFinalReview"):
    val docs = Vector(approved(Identity, "2026-08-20T10:00:00Z"), pending(ProofOfAddress, "2026-08-21T10:00:00Z"))
    assert(Rollup.readyForFinalReview(docs, Vector.empty))
    assert(Rollup.readyForFinalReview(docs, Vector(Identity)))
    assert(!Rollup.readyForFinalReview(docs, Vector(Identity, ProofOfAddress)))
    assert(!Rollup.readyForFinalReview(docs, Vector(BankAccountDetails)))

  test("NewDocTypeRequiredTypes"):
    for c <- Vector(PaymentMethod, BankAccountDetails) do
      val id = approved(Identity, "2026-09-01T10:00:00Z")
      assertEquals(status(id, approved(c, "2026-09-02T10:00:00Z"))(Identity, c), Status.Verified, c)
      assertEquals(status(id, pending(c, "2026-09-02T10:00:00Z"))(Identity, c), Status.Pending, c)
      assertEquals(status(id, rejected(c, "2026-09-02T10:00:00Z"))(Identity, c), Status.Rejected, c)
      assertEquals(status(id)(Identity, c), Status.Pending, c)

  // handler_test.go

  private val identityApproved = Vector(approved(Identity, "2026-08-20T10:00:00Z"))
  private val approvedFront    = Vector(approved(IdentityFront, "2026-09-01T10:00:00Z"))

  /** handler_test.go's Params with a fresh document event, and `completeProfileParams` as `complete`. */
  private def inputs(
      docs: Vector[Doc] = identityApproved,
      current: Status = Status.Pending,
      complete: Boolean = true,
      required: Vector[Category] = Vector(Identity),
      mode: Mode = Mode.Automatic,
      playerRequired: Vector[Category] = Vector.empty,
      verifiedAt: Option[Instant] = None,
      documentsChangedAt: Option[Instant] = Some(now),
      profileChangedAt: Option[Instant] = None,
      readyAt: Option[Instant] = None
  ): Inputs =
    Inputs(docs, required, playerRequired, complete, current, mode, verifiedAt, documentsChangedAt, profileChangedAt, readyAt)

  private def derive(in: Inputs, trigger: Trigger = Trigger.Documents): Derived = Rollup.derive(in, trigger)

  private val verified = Derived(Some(Status.Verified), Some(Reason.Document))

  test("FirstTimeVerification"):
    assertEquals(derive(inputs()), verified)

  test("AlreadyVerifiedReapprovalDoesNotRefire"):
    assertEquals(derive(inputs(current = Status.Verified)), Rollup.Unchanged)

  test("RejectedThenResubmittedReachesVerified"):
    val docs = Vector(rejected(Identity, "2026-08-20T10:00:00Z"), approved(Identity, "2026-08-22T10:00:00Z"))
    assertEquals(derive(inputs(docs = docs, current = Status.Rejected)), verified)

  // Restated (R4): with no trigger there is no derivation, which KycRecordSuite holds; here, deriving again
  // once the answer is applied derives nothing, so an operator's status is never recomputed by a repeat.
  test("StaleTimestampIsNoOp: a command that changes no input derives nothing"):
    val first = inputs(complete = false, current = Status.Unverified)
    val once  = derive(first)
    assertEquals(once, Derived(Some(Status.Pending), Some(Reason.Document)))
    assertEquals(derive(first.copy(current = Status.Pending)), Rollup.Unchanged)

  // The policy's fallback to identity is PolicySuite's; given that list, the account verifies.
  test("MissingKycPolicyTwinFallsBackToIdentityDefaultEndToEnd"):
    assertEquals(derive(inputs(required = Vector(Identity))), verified)

  test("ExplicitEmptyRequiredDocTypesNeverAutoVerifiesEndToEnd"):
    assertEquals(derive(inputs(required = Vector.empty)), Rollup.Unchanged)
    assertEquals(derive(inputs(required = Vector.empty, current = Status.Unverified)), Derived(Some(Status.Pending), Some(Reason.Document)))

  test("ManualVerificationModeAllRequiredDocsApprovedNoOp"):
    assertEquals(derive(inputs(complete = false, mode = Mode.Manual, playerRequired = Vector(ProofOfAddress))), Rollup.Unchanged)

  test("ManualVerificationModeRejectedDocStillNoOp"):
    val docs = Vector(rejected(Identity, "2026-08-20T10:00:00Z"))
    assertEquals(derive(inputs(docs = docs, complete = false, mode = Mode.Manual, playerRequired = Vector(Identity))), Rollup.Unchanged)

  test("AutomaticVerificationModeUnchangedBehavior"):
    assertEquals(derive(inputs(mode = Mode.Automatic)), verified)

  // An absent mode is automatic: a record starts automatic (data-model.md).
  test("AbsentVerificationModeUnchangedBehavior"):
    assertEquals(derive(inputs()), verified)

  test("ManualVerificationModeReadyForFinalReviewSetsTimestamp"):
    assertEquals(derive(inputs(mode = Mode.Manual, playerRequired = Vector(Identity))), Derived(ready = Some(true)))

  test("ManualVerificationModeAlreadyReadyDoesNotRebump"):
    val ready = Some(at("2026-08-25T09:00:00Z"))
    assertEquals(derive(inputs(mode = Mode.Manual, playerRequired = Vector(Identity), readyAt = ready)), Rollup.Unchanged)

  test("ManualVerificationModeEmptyRequiredDocTypesVacuouslyReady"):
    assertEquals(derive(inputs(docs = Vector.empty, mode = Mode.Manual)), Derived(ready = Some(true)))

  test("ManualVerificationModeNewUnapprovedRequiredCategoryClearsReadyTimestamp"):
    val in = inputs(complete = false, mode = Mode.Manual, playerRequired = Vector(Identity, ProofOfAddress), readyAt = Some(at("2026-08-25T09:00:00Z")))
    assertEquals(derive(in), Derived(ready = Some(false)))

  test("ManualVerificationModeAlreadyVerifiedNeverSetsReadyTimestamp"):
    assertEquals(derive(inputs(complete = false, current = Status.Verified, mode = Mode.Manual, playerRequired = Vector(Identity))), Rollup.Unchanged)

  test("IncompleteProfileKeepsAccountPending"):
    assertEquals(derive(inputs(docs = approvedFront, complete = false)), Rollup.Unchanged)

  test("ApprovedFrontVerifiesAgainstIdentityPolicy"):
    assertEquals(derive(inputs(docs = approvedFront)), verified)

  // The original's first half, an unrelated write, is no trigger here; its second half is this row.
  test("VerifiedAccountWithIncompleteProfileIsNotAffected"):
    val in = inputs(docs = approvedFront, complete = false, current = Status.Verified, verifiedAt = Some(ago(Duration.ofDays(30))))
    assertEquals(derive(in), Rollup.Unchanged)

  test("ProfileChangeReturnsVerifiedAccountToUnverified"):
    for mode <- Mode.values do
      val in = inputs(
        docs = approvedFront,
        current = Status.Verified,
        mode = mode,
        verifiedAt = Some(ago(hours(2))),
        documentsChangedAt = Some(ago(hours(2))),
        profileChangedAt = Some(now)
      )
      assertEquals(derive(in, Trigger.Profile), Derived(Some(Status.Unverified), Some(Reason.ProfileChanged)), mode)
      assertEquals(
        derive(in.copy(readyAt = Some(ago(hours(1)))), Trigger.Profile),
        Derived(Some(Status.Unverified), Some(Reason.ProfileChanged), ready = Some(false)),
        mode
      )

  test("ReverificationAfterProfileChangeIsKept"):
    val in = inputs(
      docs = approvedFront,
      current = Status.Verified,
      verifiedAt = Some(ago(minutes(1))),
      documentsChangedAt = Some(ago(hours(2))),
      profileChangedAt = Some(ago(minutes(5)))
    )
    assertEquals(derive(in, Trigger.Profile), Rollup.Unchanged)

  test("NoAutomaticReverificationUntilDocumentEventFollowsChange"):
    val base = inputs(docs = approvedFront, current = Status.Unverified, verifiedAt = Some(ago(hours(3))), profileChangedAt = Some(ago(minutes(5))))
    assertEquals(derive(base.copy(documentsChangedAt = Some(ago(hours(3)))), Trigger.Profile), Rollup.Unchanged)
    assertEquals(derive(base.copy(documentsChangedAt = Some(ago(minutes(12)))), Trigger.Profile), Rollup.Unchanged)
    assertEquals(derive(base.copy(documentsChangedAt = Some(now))), verified)

  test("NeverVerifiedPlayerCompletingProfileIsVerified"):
    val in = inputs(docs = approvedFront, current = Status.Unverified, documentsChangedAt = Some(ago(hours(2))), profileChangedAt = Some(now))
    assertEquals(derive(in, Trigger.Profile), Derived(Some(Status.Verified), Some(Reason.ProfileChanged)))
    assertEquals(derive(in.copy(profileComplete = false), Trigger.Profile), Derived(Some(Status.Pending), Some(Reason.ProfileChanged)))

  test("NewDocumentOnVerifiedAccountIsRecorded"):
    val docs = Vector(approved(IdentityFront, "2026-09-01T10:00:00Z"), pending(Other, "2026-09-05T10:00:00Z"))
    assertEquals(derive(inputs(docs = docs, current = Status.Verified, verifiedAt = Some(ago(hours(24))))), Derived(Some(Status.Pending), Some(Reason.Document)))

  test("VerificationWithExtendedPolicyRequiredTypes"):
    for c <- Vector(PaymentMethod, BankAccountDetails) do
      val both = Vector(approved(Identity, "2026-09-01T10:00:00Z"), approved(c, "2026-09-02T10:00:00Z"))
      assertEquals(derive(inputs(docs = both, required = Vector(Identity, c))), verified, c)
      val identityOnly = Vector(approved(Identity, "2026-09-01T10:00:00Z"))
      assertEquals(derive(inputs(docs = identityOnly, required = Vector(Identity, c))), Rollup.Unchanged, c)
