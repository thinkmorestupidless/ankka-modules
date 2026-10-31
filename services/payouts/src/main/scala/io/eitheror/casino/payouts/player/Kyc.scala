package io.eitheror.casino.payouts.player

/** A player's identity status, from the four KYC families of `casino.players` (data-model.md). */
enum Kyc(val wire: String):
  case Unverified extends Kyc("unverified")
  case Submitted  extends Kyc("submitted")
  case Pending    extends Kyc("pending")
  case Verified   extends Kyc("verified")
  case Rejected   extends Kyc("rejected")

object Kyc:
  def from(text: String): Option[Kyc] = values.find(_.wire == text)
