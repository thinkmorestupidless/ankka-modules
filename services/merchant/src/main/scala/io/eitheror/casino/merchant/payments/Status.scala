package io.eitheror.casino.merchant.payments

/**
 * A payment's statuses, exactly the original's words (`PaymentStatus`, FR-016): the awaiting two in
 * upper case, the three terminal in lower case, as existing records carry them. The frontend reads
 * `COMPLETED`, `FAILED` and `EXPIRED`; anything else passes through, so an unknown status never reads
 * as an outcome (FR-017).
 */
object Status:
  val AwaitingRedirect = "AWAITING_REDIRECT"
  val AwaitingCrypto   = "AWAITING_CRYPTO"
  val Captured         = "captured"
  val Failed           = "failed"
  val Expired          = "expired"

  val Terminal: Set[String] = Set(Captured, Failed, Expired)

  def isTerminal(status: String): Boolean = Terminal.contains(status)

  def toApi(status: String): String = status match
    case Captured => "COMPLETED"
    case Failed   => "FAILED"
    case Expired  => "EXPIRED"
    case other    => other

  /** The backoffice's filter words (FR-029): every non-terminal status counts as pending. */
  val Filters: Set[String] = Set("all", "pending", "captured", "failed", "expired")

  def matches(filter: String, status: String): Boolean = filter match
    case "all"     => true
    case "pending" => !isTerminal(status)
    case other     => other == status

/** Why a payment failed, as the player reads it (`FailureReason`). */
object FailureReason:
  val Declined  = "Payment declined"
  val Cancelled = "Payment cancelled"
  val Expired   = "Payment session expired"
  val Abandoned = "Payment not completed"
  val Unknown   = "Payment could not be processed"

  /** The code an abandon records (FR-024). */
  val AbandonedCode = "player_abandoned"
