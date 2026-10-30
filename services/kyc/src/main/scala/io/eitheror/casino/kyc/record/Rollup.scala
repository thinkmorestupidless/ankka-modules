package io.eitheror.casino.kyc.record

import java.time.Instant

/**
 * The derivation of the account's status and ready time (R4, FR-012 to FR-018): a pure port of the
 * original's `rollup.go` and `handler.go` (`tl/runtime/capabilities/kyc_status_rollup/`), held to its
 * tests by `RollupSuite`. The original's freshness window is the trigger: `KycRecord` derives after a
 * command that changed an input, and names which.
 */
object Rollup:
  /** A document as the derivation reads it; no outcome is awaiting review. */
  final case class Doc(category: Category, uploadedAt: Instant, outcome: Option[Outcome])

  /**
   * What changed an input. `Documents` is a document recorded or reviewed, and also the required
   * categories or the mode changed: everything but the profile, which is `Profile`.
   */
  enum Trigger:
    case Documents, Profile

  /** Why the system changed the status, as `player_kyc_pending` carries it (contracts/facts.md). */
  enum Reason(val wire: String):
    case Document       extends Reason("document")
    case ProfileChanged extends Reason("profile_changed")

  /** The record's inputs: the policy's required categories and the player's own, and the times. */
  final case class Inputs(
      docs: Vector[Doc],
      required: Vector[Category],
      playerRequired: Vector[Category],
      profileComplete: Boolean,
      current: Status,
      mode: Mode,
      verifiedAt: Option[Instant],
      documentsChangedAt: Option[Instant],
      profileChangedAt: Option[Instant],
      readyAt: Option[Instant]
  )

  /** What to record: a new status and why, and the ready time set (true) or cleared (false). Only changes. */
  final case class Derived(status: Option[Status] = None, reason: Option[Reason] = None, ready: Option[Boolean] = None)

  val Unchanged: Derived = Derived()

  /**
   * The latest document of each single-slot category: the greatest upload time, a tie going to the later
   * one. `other` documents are each their own and never latest. A legacy `identity` is dropped once a
   * front exists, the way a re-upload supersedes an earlier document.
   */
  def latest(docs: Vector[Doc]): Map[Category, Doc] =
    val byCategory = docs.foldLeft(Map.empty[Category, Doc]) { (acc, d) =>
      if d.category == Category.Other then acc
      else if acc.get(d.category).forall(e => !d.uploadedAt.isBefore(e.uploadedAt)) then acc.updated(d.category, d)
      else acc
    }
    if byCategory.contains(Category.IdentityFront) then byCategory - Category.Identity else byCategory

  /**
   * A required category met (FR-013): `identity` or `identity_front` by an approved front, or by an
   * approved legacy `identity` while there is no front. The back is never required: a passport has none.
   */
  private def satisfies(latest: Map[Category, Doc], required: Category): Boolean =
    val doc =
      if required == Category.Identity || required == Category.IdentityFront then
        latest.get(Category.IdentityFront).orElse(latest.get(Category.Identity))
      else latest.get(required)
    doc.exists(_.outcome.contains(Outcome.Approved))

  /**
   * The status from the documents alone (FR-012): a latest document rejected; any document awaiting
   * review, every `other` among them; every required category met, when there is one; no document at all;
   * else pending. A rejected `other` rejects nothing: the player cannot replace it. An empty required list
   * never verifies.
   */
  def status(docs: Vector[Doc], required: Vector[Category]): Status =
    val last = latest(docs).values
    if last.exists(_.outcome.contains(Outcome.Rejected)) then Status.Rejected
    else if last.exists(_.outcome.isEmpty) || docs.exists(d => d.category == Category.Other && d.outcome.isEmpty) then Status.Pending
    else if required.nonEmpty && required.forall(satisfies(latest(docs), _)) then Status.Verified
    else if docs.isEmpty then Status.Unverified
    else Status.Pending

  /** Every category required of the player met; vacuously so when none is (FR-018). */
  def readyForFinalReview(docs: Vector[Doc], required: Vector[Category]): Boolean =
    val last = latest(docs)
    required.forall(satisfies(last, _))

  /** The profile changed after `t`; with no `t`, any change counts. */
  private def changedAfter(profile: Option[Instant], t: Option[Instant]): Boolean =
    profile.exists(p => t.forall(p.isAfter))

  /** A once-verified player changed the profile after both the verification and the last document change. */
  private def awaitingReviewOfProfileChange(in: Inputs): Boolean =
    in.verifiedAt.isDefined && changedAfter(in.profileChangedAt, in.verifiedAt) && changedAfter(in.profileChangedAt, in.documentsChangedAt)

  /**
   * `handler.go`'s `Invoke`, in its order:
   *   1. a profile change on a verified account after its verification returns it to unverified, in
   *      either mode, clearing the ready time;
   *   2. manual mode moves only the ready time, on a transition;
   *   3. a profile change on an account still verified changes nothing;
   *   4. a derived verified on an account not verified is pending while the profile is incomplete, and
   *      unverified while a profile change awaits review;
   *   5. only a change is recorded (FR-014).
   */
  def derive(in: Inputs, trigger: Trigger): Derived =
    val reason = trigger match
      case Trigger.Documents => Reason.Document
      case Trigger.Profile   => Reason.ProfileChanged
    if trigger == Trigger.Profile && in.current == Status.Verified && changedAfter(in.profileChangedAt, in.verifiedAt) then
      Derived(Some(Status.Unverified), Some(Reason.ProfileChanged), Option.when(in.readyAt.isDefined)(false))
    else if in.mode == Mode.Manual then
      val ready = readyForFinalReview(in.docs, in.playerRequired) && in.current != Status.Verified && in.profileComplete
      if ready == in.readyAt.isDefined then Unchanged else Derived(ready = Some(ready))
    else if trigger == Trigger.Profile && in.current == Status.Verified then Unchanged
    else
      val derived = status(in.docs, in.required) match
        case Status.Verified if in.current != Status.Verified && !in.profileComplete => Status.Pending
        case Status.Verified if in.current != Status.Verified && awaitingReviewOfProfileChange(in) => Status.Unverified
        case s => s
      if derived == in.current then Unchanged else Derived(Some(derived), Some(reason))
