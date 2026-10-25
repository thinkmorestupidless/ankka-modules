package io.eitheror.casino.wallet.entity

/**
 * The wallet's refusal codes and messages (contracts/wire.md). The resolver's own refusals carry
 * the Go guests' messages verbatim (`funding.Messages`); these are the route-level ones. Where a
 * recording of the original shows another text, the recording wins and this table is corrected.
 */
object Refusals:
  val WalletNotFound         = "WALLET_NOT_FOUND"
  val WalletAlreadyOpen      = "WALLET_ALREADY_OPEN"
  val RoundNotFound          = "ROUND_NOT_FOUND"
  val KeyReused              = "KEY_REUSED"
  val BonusAlreadyActive     = "BONUS_ALREADY_ACTIVE"
  val MissingBonusRef        = "MISSING_BONUS_REF"
  val GrantNotFound          = "GRANT_NOT_FOUND"
  val GrantNotActive         = "GRANT_NOT_ACTIVE"
  val RequestNotPending      = "REQUEST_NOT_PENDING"
  /** The original's code when a player's cancel names a request that is not the pending one. */
  val RequestNotCancellable  = "WITHDRAWAL_REQUEST_NOT_CANCELLABLE"
  val WithdrawalNotCompleted = "WITHDRAWAL_NOT_IN_COMPLETED_STATE"
  val ReasonRequired         = "REASON_REQUIRED"

  // spec 022: reservations, settlement, non-player accounts (data-model.md)
  val ReservationNotFound          = "RESERVATION_NOT_FOUND"
  val ReleaseExceedsReservation    = "RELEASE_EXCEEDS_RESERVATION"
  val ReservationShort             = "RESERVATION_SHORT"
  val AccountNotFound              = "ACCOUNT_NOT_FOUND"
  val NotOwner                     = "NOT_OWNER"
  val CreditLineReached            = "CREDIT_LINE_REACHED"
  val NotClearing                  = "NOT_CLEARING"
  val CreditLineBelowBalance       = "CREDIT_LINE_BELOW_BALANCE"

  val walletNotFound    = "Wallet not found"
  val walletAlreadyOpen = "Wallet already open"
  val bonusAlreadyActive = "A bonus is already active"
  val missingBonusRef   = "bonus_ref is required"
  val reasonRequired    = "A reason must be supplied for a manual balance adjustment"

  def roundNotFound(round: String): String           = s"No open bet found for round $round"
  def grantNotFound(grant: String): String           = s"Grant $grant not found"
  def grantNotActive(grant: String): String          = s"Grant $grant is not active"
  def requestNotPending(request: String): String     = s"Request $request is not the pending request"
  def requestNotCancellable(request: String): String = s"Request $request is not cancellable"
  def withdrawalNotCompleted(request: String): String = s"Withdrawal $request is not completed"
  /** The original's text; `target` is real or bonus, as its admin-adjust names the balance. */
  def debitExceeds(amount: Long, target: String, balance: Long): String =
    s"Requested debit of $amount exceeds current $target balance of $balance"

  def reservationNotFound(order: String): String = s"No reservation stands for order $order"
  def releaseExceeds(amount: Long, reserved: Long): String =
    s"Release of $amount exceeds the $reserved reserved for the order"
  def reservationShort(required: Long, reserved: Long): String =
    s"Settlement needs $required of the reservation and $reserved is reserved"
  def accountNotFound(account: String): String = s"No account $account"
  def notOwner(account: String, owner: String): String = s"Account $account is owned by $owner"
  def poolShort(amount: Long, balance: Long): String =
    s"Debit of $amount would take the pool account below zero from $balance"
  def creditLineReached(amount: Long, balance: Long, floor: Long): String =
    s"Debit of $amount from $balance would pass the credit line's floor of $floor"
  def notClearing(account: String): String = s"Account $account is not a clearing account and has no credit line"
  def creditLineBelowBalance(line: Long, balance: Long): String =
    s"A credit line of $line is less than the $balance the account already owes"

  /** FR-002: names the key and the first command's type and amount. */
  def keyReused(key: String, first: Outcome): String =
    s"Key $key was used by a ${first.commandType}${first.amount.fold("")(a => s" of $a")}"
