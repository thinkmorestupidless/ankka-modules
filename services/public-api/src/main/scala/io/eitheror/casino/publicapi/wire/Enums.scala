package io.eitheror.casino.publicapi.wire

/**
 * One map per contract enum, from the word a record holds (contracts/fields.md §2), ported arm for arm from the
 * original's `mappers/enums.ts`. A word with no published equivalent is `None`, served as null, never coerced
 * (original FR-009). Lookups are exact.
 */
object Enums:
  val PlayerStatus: Map[String, String] =
    Map("active" -> "ACTIVE", "suspended" -> "SUSPENDED", "closed" -> "CLOSED", "self_excluded" -> "SELF_EXCLUDED")

  val KycStatus: Map[String, String] =
    Map("unverified" -> "UNVERIFIED", "pending" -> "PENDING", "verified" -> "VERIFIED", "rejected" -> "REJECTED")

  val TransactionType: Map[String, String] = Map(
    "deposit" -> "DEPOSIT", "withdrawal" -> "WITHDRAWAL", "withdrawal_return" -> "WITHDRAWAL", "withdrawal_reversal" -> "WITHDRAWAL",
    "bet" -> "BET", "win" -> "WIN", "bonus_credit" -> "BONUS", "bonus_conversion" -> "BONUS", "bonus_cap_void" -> "BONUS",
    "bonus_cancel" -> "BONUS", "rollback" -> "ROLLBACK", "loss" -> "LOSS", "admin_adjustment" -> "ADMIN_ADJUSTMENT",
    "game_provider_credit" -> "GAME_PROVIDER_CREDIT")

  /** Shared by a transaction's `walletType` and a bet's `fundedFrom`. */
  val WalletType: Map[String, String] = Map("real" -> "MAIN", "bonus" -> "BONUS", "split" -> "SPLIT")

  val SessionStatus: Map[String, String] =
    Map("active" -> "OPEN", "completed" -> "CLOSED", "expired" -> "CLOSED", "force_closed" -> "CLOSED")

  val BonusStatus: Map[String, String] = Map(
    "active" -> "ACTIVE", "ACTIVE" -> "ACTIVE", "wagering" -> "WAGERING", "WAGERING" -> "WAGERING", "completed" -> "COMPLETED",
    "expired" -> "EXPIRED", "CANCELLED" -> "CANCELLED", "forfeited" -> "FORFEITED", "FORFEITED" -> "FORFEITED",
    "CONVERTED_PENDING_CAP" -> "CONVERTED_PENDING_CAP", "CLOSED" -> "CLOSED")

  def playerStatus(word: String): Option[String]    = PlayerStatus.get(word)
  def kycStatus(word: String): Option[String]       = KycStatus.get(word)
  def transactionType(word: String): Option[String] = TransactionType.get(word)
  def walletType(word: String): Option[String]      = WalletType.get(word)
  def sessionStatus(word: String): Option[String]   = SessionStatus.get(word)
  def bonusStatus(word: String): Option[String]     = BonusStatus.get(word)

  /**
   * The original's type word for a `money.transactions` family (contracts/fields.md §2's table), `None` for a family
   * that is not a transaction of the public API (`restricted_funds_closed`, spec 022's seven, `account_adjusted`).
   * A forfeit is `bonus_credit` unless its reason is `cancel` or `hold` (`bonus_cancel`).
   */
  def typeWordOf(family: String, reason: Option[String] = None): Option[String] = family match
    case "deposit_confirmed"           => Some("deposit")
    case "bet_placed"                  => Some("bet")
    case "win_credited"                => Some("win")
    case "bet_rolled_back"             => Some("rollback")
    case "round_settled_as_loss"       => Some("loss")
    case "restricted_funds_awarded"    => Some("bonus_credit")
    case "restricted_funds_converted"  => Some("bonus_conversion")
    case "restricted_funds_forfeited"  => Some(if reason.exists(r => r == "cancel" || r == "hold") then "bonus_cancel" else "bonus_credit")
    case "admin_adjusted"              => Some("admin_adjustment")
    case "hold_placed"                 => Some("withdrawal")
    case "hold_released"               => Some("withdrawal_return")
    case "withdrawal_reversed"         => Some("withdrawal_reversal")
    case "provider_credited"           => Some("game_provider_credit")
    case _                             => None

  /** A wallet side word on a fact (`real`, `restricted`) as the contract's wallet type. */
  def side(word: String): Option[String] = word match
    case "real"       => Some("MAIN")
    case "restricted" => Some("BONUS")
    case _            => None
