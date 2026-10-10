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

  /** FR-002: names the key and the first command's type and amount. */
  def keyReused(key: String, first: Outcome): String =
    s"Key $key was used by a ${first.commandType}${first.amount.fold("")(a => s" of $a")}"
