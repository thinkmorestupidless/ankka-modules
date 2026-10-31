package io.eitheror.casino.kyc.policy

import io.eitheror.casino.kyc.record.Category

/**
 * The licence profiles of research R9, from the original's `BackofficeKycPolicyRoutes.scala` and
 * `KycPolicy.curacaoDefault`, with the KYC currency each threshold is in (Clarifications 2026-10-30).
 * Applying one sets every field.
 */
object Profiles:
  val Curacao: Policy = Policy(
    depositGate = Gate.Off,
    playGate = Gate.Off,
    currency = None,
    depositThreshold = None,
    sofThreshold = None,
    windowDays = Some(30),
    required = Vector(Category.Identity),
    suspendOnExpiry = true,
    profile = Profile.Curacao
  )

  val Mga: Policy = Policy(
    depositGate = Gate.Off,
    playGate = Gate.Off,
    currency = Some("EUR"),
    depositThreshold = Some(200000L),
    sofThreshold = Some(1000000L),
    windowDays = None,
    required = Vector(Category.Identity, Category.ProofOfAddress),
    suspendOnExpiry = false,
    profile = Profile.Mga
  )

  val Ukgc: Policy = Policy(
    depositGate = Gate.KycApproved,
    playGate = Gate.KycApproved,
    currency = Some("GBP"),
    depositThreshold = None,
    sofThreshold = Some(200000L),
    windowDays = Some(3),
    required = Vector(Category.Identity, Category.ProofOfAddress),
    suspendOnExpiry = true,
    profile = Profile.Ukgc
  )

  private val byName: Map[String, Policy] = Map("CURACAO" -> Curacao, "MGA" -> Mga, "UKGC" -> Ukgc)

  /** The names an operator may apply, sorted as the original answers them. */
  val known: Vector[String] = byName.keys.toVector.sorted

  /** The profile named, in any case; a blank name is `profile_required`, an unknown one names the known. */
  def of(name: Option[String]): Either[PolicyRefusal, Policy] =
    name.map(_.trim).filter(_.nonEmpty) match
      case None    => Left(PolicyRefusal.ProfileRequired)
      case Some(n) => byName.get(n.toUpperCase).toRight(PolicyRefusal.UnknownProfile(known))
