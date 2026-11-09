package io.eitheror.casino.support.domain

import io.eitheror.casino.contract.money.{Amounts, Currency}

import java.time.Instant

/**
 * What the player is asked to confirm before a write (contracts/tools.md), composed from the tool's arguments,
 * never by the model: the original's texts, with amounts in major units at the currency's exponent. The
 * statements FR-022 requires are in the text itself, so no argument can leave them out (research R8).
 */
object Summaries:

  def major(minor: Long, currency: Currency): String = Amounts.render(minor, currency)

  /** `forfeit` is the bonus funds the wallet's hold preview says the withdrawal forfeits. */
  def withdrawal(amount: Long, currency: Currency, forfeit: Option[Long]): String =
    val base = s"Withdraw ${major(amount, currency)} ${currency.code} from your account"
    forfeit.filter(_ > 0).fold(base)(f => s"$base — this forfeits ${major(f, currency)} ${currency.code} of bonus funds")

  def closeSession(gameSessionId: String): String = s"Close your active game session (session ID: $gameSessionId)"

  def depositLimit(period: String, amount: Long, currency: Currency): String =
    s"Set a $period deposit limit of ${major(amount, currency)}. Note: this limit will not take effect immediately due to " +
      "regulatory requirements — there is a mandatory cooling-off delay before it applies."

  def coolOff(until: Instant): String = s"Apply a cool-off period until $until. This cannot be shortened once it is in effect."

  val selfExclusion: String = "Self-exclude your account permanently. This is permanent and cannot be reversed."
