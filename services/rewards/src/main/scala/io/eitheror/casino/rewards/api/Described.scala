package io.eitheror.casino.rewards.api

import com.thinkmorestupidless.ankka.http.openapi.Description

/**
 * What the player's rewards routes take and answer, for their descriptions (spec 031,
 * contracts/descriptions.md). The routes build their JSON as trees (`PlayerState`, `PlayerLifecycle`,
 * `Codes`, `FreeRoundsReads`, `PlayerGrants`), so these types exist only to give the document their
 * shapes, field for field. Amounts are minor units.
 */
private[api] object Described:

  /** `Answers.refusal`: the code, and the fields it names. */
  final case class Refusal(error: String, message: Option[String])

  /** A 409: a refusal `{error}`, or the evaluator's decline as the original answered it, `{status: "ineligible", reason}`. */
  final case class Conflict(error: Option[String], message: Option[String], status: Option[String], reason: Option[String])

  /** `PlayerState.summary`: an offer as the player reads it. */
  final case class Offer(offerId: String, role: Option[String], source: String, name: String, description: String,
      claimWindowDays: Option[Int], `type`: String, bonusPercentage: Long, maxBonusValue: Option[Long],
      minDepositAmount: Option[Long], wageringMultiplier: Long, wageringWindowDays: Option[Int], noDepositRequired: Boolean)

  final case class Selected(offerId: String, name: String, selectedAt: Option[String], expiresAt: Option[String])

  final case class ActiveBonus(recordId: String, grantRef: String, bonusBalance: Long, currency: String,
      wageringRequirement: Long, wageredAmount: Long, maxWin: Long, offer: Option[Offer])

  /**
   * `PlayerState.of`: `active` with the wallet's open records and the primary's fields at the top,
   * `pending` with the selection and the offers it competes with, or `available` with the offers.
   */
  final case class State(state: String, activeBonuses: Vector[ActiveBonus], bonusBalance: Option[Long], currency: Option[String],
      wageringRequirement: Option[Long], wageredAmount: Option[Long], maxWin: Option[Long], offer: Option[Offer],
      selected: Option[Selected], competingOffers: Vector[Offer], visibleOffers: Vector[Offer])

  final case class Select(offerId: String)
  final case class SelectedOffer(ok: Boolean, expiresAt: Option[String])
  final case class Ok(ok: Boolean)
  final case class Cancel(grantRef: Option[String])
  final case class Cancelled(status: String, clawbackAmount: Long, newRealBalance: Long)
  final case class Switch(grantRef: String)
  final case class Closed(grantRef: String, faceValue: Long, currency: String, status: String, grantedAt: Option[String],
      closedAt: Option[String])
  final case class Code(code: String)
  final case class Preview(valid: Boolean, error: Option[String], offerName: Option[String], offerType: Option[String],
      preview: Option[String], firesImmediately: Option[Boolean], minDepositAmount: Option[Long], isAffiliate: Option[Boolean])
  final case class FreeRounds(id: String, gameId: String, gameName: Option[String], spinsRemaining: Int,
      expiresAt: Option[String], providerStatus: Option[String])
  final case class Grant(rewardId: String, offerName: String, status: String, wageringRequirement: Long, releaseProgress: Long,
      expiresAt: Option[String], currency: String)

  val refusals: Description = Description.none.refusalShape[Refusal]

  val mine = "player_id_mismatch"
