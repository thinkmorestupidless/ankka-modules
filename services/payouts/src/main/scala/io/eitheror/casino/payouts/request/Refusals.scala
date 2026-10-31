package io.eitheror.casino.payouts.request

import io.eitheror.casino.payouts.limits.Block

/** A value a refusal names beside its code (contracts/routes.md). */
enum Field:
  case Num(value: Long)
  case Text(value: String)
  case Texts(values: Vector[String])

/** `{error: code, message, …fields}` with its status; every refusal of the request path is one. */
final case class Refusal(status: Int, code: String, message: String, fields: Vector[(String, Field)] = Vector.empty)

/**
 * The request path's refusals, each with the original's code and status (R4; contracts/routes.md). The
 * KYC messages are the original's, verbatim (`WithdrawalRoutes.scala:252-256`).
 */
object Refusals:
  import Field.*

  val MissingKey      = Refusal(400, "MISSING_KEY", "key is required")
  val InvalidAmount   = Refusal(422, "INVALID_AMOUNT", "the amount is not a positive whole number of minor units")
  val PlayerNotFound  = Refusal(404, "PLAYER_NOT_FOUND", "no such player")
  val WalletNotFound  = Refusal(404, "WALLET_NOT_FOUND", "no wallet in this currency")
  val Unavailable     = Refusal(503, "SERVICE_UNAVAILABLE", "the wallet did not answer in time; try again")
  val AlreadyPending  = Refusal(409, "WITHDRAWAL_REQUEST_ALREADY_PENDING", "A withdrawal request is already pending review")
  val Insufficient    = Refusal(402, "INSUFFICIENT_FUNDS", "Insufficient funds")
  val Turnover        = Refusal(402, "WITHDRAWAL_BLOCKED_TURNOVER", "Deposit turnover requirements not yet met")
  val DestinationRequired = Refusal(422, "DESTINATION_REQUIRED", "this terminal pays out to a saved destination")
  val AddressRequired     = Refusal(422, "WALLET_ADDRESS_REQUIRED", "this terminal pays out to a wallet address")
  val InvalidDestination  = Refusal(422, "INVALID_PAYOUT_DESTINATION", "the destination is not one of yours, or was deleted")
  val KeyReused           = Refusal(409, "KEY_REUSED", "the key was used for a different request")

  def required(field: String): Refusal = Refusal(400, "INVALID_REQUEST", s"$field is required", Vector("field" -> Text(field)))

  def kyc(status: String): Refusal =
    val message = status match
      case "unverified" => "Please verify your identity before withdrawing"
      case "pending"    => "Your identity documents are under review"
      case "rejected"   => "Your identity documents were not accepted — please re-submit"
      case _            => "KYC verification required"
    Refusal(402, "WITHDRAWAL_BLOCKED_KYC", message, Vector("kycStatus" -> Text(status)))

  def limit(block: Block, currency: String): Refusal =
    val (code, word) = block match
      case _: Block.Weekly  => ("WITHDRAWAL_BLOCKED_WEEKLY_LIMIT", "weekly")
      case _: Block.Monthly => ("WITHDRAWAL_BLOCKED_MONTHLY_LIMIT", "monthly")
    // The original's names (weeklyLimit, weeklyUsed, …), which its player site reads.
    Refusal(402, code, s"the amount would exceed the $word withdrawal limit",
      Vector(s"${word}Limit" -> Num(block.limit), s"${word}Used" -> Num(block.used), s"${word}Remaining" -> Num(block.remaining),
        "currency" -> Text(currency)))

  def afterForfeit(maxWithdrawable: Long, totalForfeit: Long): Refusal =
    Refusal(422, "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT", "insufficient real balance after bonus forfeiture",
      Vector("maxWithdrawable" -> Num(maxWithdrawable), "totalForfeit" -> Num(totalForfeit)))

  def confirmForfeit(bonusBalance: Long, clawback: Long, totalForfeit: Long): Refusal =
    Refusal(402, "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION", "withdrawing forfeits the active bonus; confirm to go on",
      Vector("bonusBalance" -> Num(bonusBalance), "clawbackAmount" -> Num(clawback), "totalForfeit" -> Num(totalForfeit)))

  def terminalNotEligible(terminalId: String): Refusal =
    Refusal(422, "TERMINAL_NOT_ELIGIBLE", "this terminal is not offered to you", Vector("terminalId" -> Text(terminalId)))

  def coin(coin: String, supported: Vector[String]): Refusal =
    Refusal(422, "CRYPTO_CURRENCY_NOT_SUPPORTED", "this terminal does not pay out in that coin",
      Vector("cryptoCurrency" -> Text(coin), "supported" -> Texts(supported)))
