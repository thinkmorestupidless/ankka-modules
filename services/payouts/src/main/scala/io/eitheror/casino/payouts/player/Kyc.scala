package io.eitheror.casino.payouts.player

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.payouts.PayoutsCodecs

/** A player's identity status, from the four KYC families of `casino.players` (data-model.md). */
enum Kyc(val wire: String):
  case Unverified extends Kyc("unverified")
  case Submitted  extends Kyc("submitted")
  case Pending    extends Kyc("pending")
  case Verified   extends Kyc("verified")
  case Rejected   extends Kyc("rejected")

object Kyc:
  def from(text: String): Option[Kyc] = values.find(_.wire == text)
  given JsonValueCodec[Kyc]          = PayoutsCodecs.wire(values, _.wire)
