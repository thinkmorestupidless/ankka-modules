package io.eitheror.casino.wallet.wire

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter, writeToArray}
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.money.{AmountNotMinorUnits, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.wallet.entity.{DepositSums, Outcome}
import io.eitheror.casino.wallet.funding.Leg

/**
 * The wallet's HTTP bodies (contracts/routes.md). Field names are the original's where it has the
 * route (`realBalance`, `bonusBalance`, `txnId`, `walletType`: camelCase, as its spray-json wrote
 * them; research R11); amounts on service and granted routes are minor-unit integers (FR-003).
 */

/**
 * An amount as the caller sent it, the token exactly: the boundary decides whether it is a whole
 * number of minor units and names it when it is not (spec 001 FR-026), before any state is read.
 */
opaque type AmountText = String

object AmountText:
  def apply(text: String): AmountText = text
  extension (a: AmountText)
    def text: String                                          = a
    def minorUnits: Either[AmountNotMinorUnits, MinorUnits]   = MinorUnits.parse(a)
    def signed: Either[AmountNotMinorUnits, SignedMinorUnits] = SignedMinorUnits.parse(a)

  given JsonValueCodec[AmountText] with
    def decodeValue(in: JsonReader, default: AmountText): AmountText =
      String(in.readRawValAsBytes(), java.nio.charset.StandardCharsets.UTF_8).trim.stripPrefix("\"").stripSuffix("\"")
    def encodeValue(x: AmountText, out: JsonWriter): Unit = out.writeRawVal(x.getBytes("UTF-8"))
    def nullValue: AmountText                             = null.asInstanceOf[AmountText]

// ── requests ────────────────────────────────────────────────────────────────

final case class BetRequest(key: Option[String], roundId: String, gameId: String, sessionId: String = "", provider: String = "",
    gameType: String = "", category: String = "", amount: AmountText, allowNegative: Option[Boolean] = None)
final case class WinRequest(key: Option[String], roundId: String, amount: AmountText, betKey: Option[String] = None)
final case class RollbackRequest(key: Option[String], roundId: String, betKey: Option[String] = None)
final case class LossRequest(key: Option[String], roundId: String, betKey: Option[String] = None)
final case class ProviderCreditRequest(key: Option[String], amount: AmountText, providerRef: String = "", reason: String = "", actor: String = "")
final case class AwardRequest(key: Option[String], bonusRef: Option[String] = None, amount: AmountText, wageringMultiplier: Long = 0L,
    maxWin: Long = 0L, priority: Option[Long] = None, expiresAt: Option[java.time.Instant] = None, maxStake: Option[Long] = None,
    maxStakeEnforcement: String = "", contributionDefault: Option[Long] = None, contributionOverrides: Map[String, Long] = Map.empty,
    scopeInclude: Set[String] = Set.empty, scopeExclude: Set[String] = Set.empty, scopeViolationMode: String = "",
    fundingOrder: String = "", winAllocation: String = "", releaseProgressScope: String = "", forfeitOnWithdrawal: String = "")
final case class GrantRequest(key: Option[String], grantRef: Option[String] = None)
final case class KeyRequest(key: Option[String])
final case class HoldRequest(key: Option[String], amount: AmountText, playerConfirmed: Boolean = false)
final case class ReleaseRequest(key: Option[String], outcome: String)
final case class AdjustRequest(key: Option[String], walletType: String = "real", amount: AmountText, reason: String = "",
    category: String = "other", actor: String = "")
final case class DepositRequest(paymentId: Option[String], amount: AmountText, terminal: String = "", psp: String = "",
    scheme: String = "", paymentProvider: String = "")
final case class OutcomeRequest(outcome: String, playerId: String, paymentProvider: String, terminal: Option[String] = None,
    amount: Option[Long] = None, currency: Option[String] = None, code: Option[String] = None, reason: Option[String] = None,
    disputeOutcome: Option[String] = None)
final case class BetSwitchesRequest(contributionRateByGameType: Map[String, Long] = Map.empty, bonusWageringCounts: Boolean = true,
    partialSplitsAllowed: Boolean = true, maxConcurrentAwards: Option[Int] = None)
final case class TurnoverTermsRequest(multiplier: Long, definition: String = "gross_wagered")
final case class TurnoverOverrideRequest(multiplier: Option[Long] = None, definition: Option[String] = None)

// ── replies ─────────────────────────────────────────────────────────────────

/** An accepted money command's reply: the fields its route names (contracts/routes.md); absent ones are left out. */
final case class MoneyReply(
    realBalance: Long,
    bonusBalance: Long,
    currency: String,
    txnId: Option[String] = None,
    walletType: Option[String] = None,
    realLeg: Option[Long] = None,
    recordLegs: Vector[Leg] = Vector.empty,
    conversions: Vector[String] = Vector.empty,
    forfeited: Vector[String] = Vector.empty,
    clawbackAmount: Option[Long] = None,
    recordId: Option[String] = None,
    wageringRequirement: Option[Long] = None,
    priority: Option[Long] = None,
    alreadyPrimary: Option[Boolean] = None,
    confirmationRequired: Option[Boolean] = None,
    voidedAmount: Option[Long] = None,
    paidAmount: Option[Long] = None,
    cancelGrants: Vector[String] = Vector.empty,
    landedOn: Option[String] = None,
    depositOrdinal: Option[Int] = None,
    turnoverRequired: Option[Long] = None,
    depositSums: Option[DepositSums] = None,
    closedRecordId: Option[String] = None,
    balanceBefore: Option[Long] = None,
    balanceAfter: Option[Long] = None,
    /** Spec 025: both pools after the command, and the deposit part of a bet's real leg or a win's real share. */
    depositPoolBalance: Option[Long] = None,
    convertedPoolBalance: Option[Long] = None,
    fundedDepositAmount: Option[Long] = None,
    wonDepositAmount: Option[Long] = None,
    /** Spec 022: on the reservation, settlement and account replies only; a spec 002 reply is unchanged. */
    reserved: Option[Long] = None,
    available: Option[Long] = None
)

/** A refusal: the original's `error` code, the message, and the amounts it names. */
final case class RefusalBody(error: String, message: String, details: Map[String, String] = Map.empty)

// ── spec 022 (contracts/routes.md) ──────────────────────────────────────────

final case class ReservationRequest(key: Option[String], amount: AmountText)
final case class ReleaseReservationRequest(orderId: Option[String], sequence: Option[Long], amount: AmountText)
final case class SettlementRequest(tradeId: Option[String], currency: String, buyer: String, orderId: String, seller: String,
    amount: AmountText, unused: Option[AmountText] = None)
final case class AccountMoveRequest(key: Option[String], currency: String, player: String, amount: AmountText,
    direction: Option[String] = None)
final case class ResolutionPaymentRequest(positionId: Option[String], currency: String, player: String, amount: AmountText)
final case class CreditLineRequest(currency: String, creditLine: AmountText)

final case class SettlementBuyer(realBalance: Long, reserved: Long, available: Long, txnId: Option[String])
final case class SettlementSeller(realBalance: Long, txnId: Option[String])
final case class SettlementReply(buyer: SettlementBuyer, seller: SettlementSeller, currency: String)

final case class MoveWallet(realBalance: Long, reserved: Long, available: Long, txnId: Option[String])
final case class MoveAccount(balance: Long, txnId: Option[String])
final case class AccountMoveReply(wallet: MoveWallet, account: MoveAccount, currency: String)

final case class AccountBalanceReply(balance: Long, creditLine: Long, floor: Long, owner: String, currency: String)

object Wire:
  given JsonValueCodec[MoneyReply]  = io.eitheror.casino.wallet.WalletCodecs.wire[MoneyReply]
  given JsonValueCodec[RefusalBody] = io.eitheror.casino.wallet.WalletCodecs.wire[RefusalBody]

  /** The status of each refusal (contracts/wire.md); unlisted codes are a conflict with the wallet's state. */
  def status(code: String): Int = code match
    case "INVALID_REQUEST" | "MISSING_KEY" | "REASON_REQUIRED" | "MISSING_BONUS_REF" | "DEBIT_EXCEEDS_BALANCE" => 400
    case "WALLET_NOT_FOUND" | "ROUND_NOT_FOUND" | "GRANT_NOT_FOUND"                                           => 404
    case "RESERVATION_NOT_FOUND" | "ACCOUNT_NOT_FOUND"                                                        => 404
    case "NOT_CLEARING" | "CREDIT_LINE_BELOW_BALANCE"                                                         => 400
    case "PLAYER_LOCKED" | "EXCLUDED" | "COOLING_OFF" | "player_id_mismatch" | "NOT_OWNER"                     => 403
    case "RG_UNAVAILABLE" | "OUTCOME_PENDING"                                                                   => 503
    case _                                                                                                      => 409

  def json[A](status: Int, body: A)(using codec: JsonValueCodec[A]): Respond[Bytes] =
    Respond(Bytes("application/json", writeToArray(body)), status)

  def refusal(code: String, message: String, details: Map[String, String] = Map.empty): Respond[Bytes] =
    json(status(code), RefusalBody(code, message, details))

  /** An outcome as its route answers it: the reply, or the refusal with its status. */
  def outcome(o: Outcome, currency: String): Respond[Bytes] =
    o.code match
      case Some(code) => refusal(code, o.message.getOrElse(code), o.details)
      case None =>
        json(200, MoneyReply(
          realBalance = o.realBalance, bonusBalance = o.restrictedBalance, currency = currency, txnId = o.transactionId,
          walletType = o.walletType, realLeg = o.realLeg, recordLegs = o.recordLegs, conversions = o.conversions,
          forfeited = o.forfeited, clawbackAmount = o.clawbackApplied, recordId = o.recordId,
          wageringRequirement = o.wageringRequirement, priority = o.priority, alreadyPrimary = o.alreadyPrimary,
          confirmationRequired = o.confirmationRequired, voidedAmount = o.voided, paidAmount = o.paid,
          cancelGrants = o.cancelGrants, landedOn = o.landedOn, depositOrdinal = o.depositOrdinal,
          turnoverRequired = o.turnoverRequired, depositSums = o.depositSums, closedRecordId = o.closedRecordId,
          balanceBefore = o.balanceBefore, balanceAfter = o.balanceAfter,
          depositPoolBalance = o.depositPool, convertedPoolBalance = o.convertedPool,
          fundedDepositAmount = o.parts.filter(_ => o.commandType == "place-bet").map(_.deposit),
          wonDepositAmount = o.parts.filter(_ => o.commandType == "credit-win").map(_.deposit),
          reserved = o.reserved, available = o.available
        ))

  /** The boundary refusal for an amount that is not whole minor units (spec 001 FR-026). */
  def invalidAmount(e: AmountNotMinorUnits): Respond[Bytes] = refusal("INVALID_REQUEST", e.message, Map("amount" -> e.amount))

  /** The boundary refusal for a money command without its key (spec 001 FR-027). */
  val missingKey: Respond[Bytes] =
    refusal("MISSING_KEY", io.eitheror.casino.contract.money.MissingKey.message)

  /** Major-unit text with two decimals, for the player site and the backoffice (FR-003). */
  def major(minor: Long): String =
    val sign = if minor < 0 then "-" else ""
    val abs  = math.abs(minor)
    f"$sign${abs / 100}%d.${abs % 100}%02d"
