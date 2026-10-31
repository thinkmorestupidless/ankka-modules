package io.eitheror.casino.kyc.record

import io.eitheror.casino.kyc.policy.{Gate, Policy}

/**
 * A gate's answer (R5): allowed, or refused with the code and the original's message, and the flag a
 * refusal raises. The record raises a flag only when none of its reason is open.
 */
final case class GateAnswer(allowed: Boolean, code: Option[String] = None, message: Option[String] = None, flag: Option[FlagReason] = None)

/** The deposit check (FR-019) and the play check (FR-020), in their order, with research R5's messages verbatim. */
object Gates:
  val Allowed: GateAnswer = GateAnswer(allowed = true)

  private def refused(code: String, message: String, flag: Option[FlagReason] = None) = GateAnswer(false, Some(code), Some(message), flag)

  private def depositMessage(status: Status): String = status match
    case Status.Pending  => "Your identity documents are under review"
    case Status.Rejected => "Your identity documents were not accepted — please re-submit"
    case _               => "Please verify your identity before depositing"

  private def playMessage(status: Status): String = status match
    case Status.Pending  => "Your identity documents are under review"
    case Status.Rejected => "Your identity documents were not accepted — please re-submit"
    case _               => "Please verify your identity before playing"

  /**
   * `lifetime` is the player's lifetime deposits in the policy's KYC currency. A check with no currency is
   * one in another currency: refused while the policy has a threshold, not compared while it has none.
   * Exactly at a threshold is allowed; a minor unit over is refused.
   */
  def deposit(policy: Policy, status: Status, sof: Sof, lifetime: Long, currency: Option[String], amount: Long): GateAnswer =
    val verified     = status == Status.Verified
    val hasThreshold = policy.depositThreshold.isDefined || policy.sofThreshold.isDefined
    def over(threshold: Option[Long]) = threshold.exists(lifetime + amount > _)
    if policy.depositGate == Gate.KycApproved && !verified then refused("DEPOSIT_BLOCKED_KYC", depositMessage(status))
    else if hasThreshold && (currency.isEmpty || currency != policy.currency) then
      refused("DEPOSIT_BLOCKED_KYC_CURRENCY", "Deposits in this currency are not accepted")
    else if !verified && over(policy.depositThreshold) then
      refused("DEPOSIT_BLOCKED_KYC_THRESHOLD", "Your account requires identity verification before further deposits", Some(FlagReason.Threshold))
    else if sof != Sof.Cleared && over(policy.sofThreshold) then
      refused("DEPOSIT_BLOCKED_SOF", "Your account requires a source of funds review before further deposits", Some(FlagReason.SourceOfFunds))
    else Allowed

  def play(policy: Policy, status: Status): GateAnswer =
    if policy.playGate == Gate.KycApproved && status != Status.Verified then refused("PLAY_BLOCKED_KYC", playMessage(status))
    else Allowed
