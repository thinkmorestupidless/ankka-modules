package io.eitheror.casino.payouts.player

import java.text.Normalizer

/**
 * The holder names a destination must carry (R13, `PayoutDestinationRoutes.scala:43-45, 190-218`): both
 * equal to the profile's once trimmed, lower-cased and stripped of diacritics. Both profile names blank is
 * `PROFILE_INCOMPLETE`; a name that differs is the original's `PAYOUT_DESTINATION_AML_BLOCKED`.
 */
object Names:
  enum Refusal(val code: String):
    case ProfileIncomplete extends Refusal("PROFILE_INCOMPLETE")
    case Mismatch          extends Refusal("PAYOUT_DESTINATION_AML_BLOCKED")

  def normalise(raw: String): String =
    Normalizer.normalize(raw.trim.toLowerCase, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}", "")

  /** The profile's `(given, family)`, none when no name was ever seen. */
  def check(givenName: String, familyName: String, profile: Option[(String, String)]): Either[Refusal, Unit] =
    val (pg, pf) = profile.getOrElse(("", ""))
    if pg.isBlank && pf.isBlank then Left(Refusal.ProfileIncomplete)
    else if normalise(givenName) != normalise(pg) || normalise(familyName) != normalise(pf) then Left(Refusal.Mismatch)
    else Right(())
