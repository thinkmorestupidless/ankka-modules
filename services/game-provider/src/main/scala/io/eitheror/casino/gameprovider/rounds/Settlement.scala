package io.eitheror.casino.gameprovider.rounds

/** One call to the wallet a credit decides on. */
enum WalletCall:
  case Win(key: String, walletRoundId: String, amount: Long, bet: String)
  case Loss(key: String, walletRoundId: String, bet: String)
  case ProviderCredit(key: String, amount: Long)

/**
 * Which wallet calls a credit makes (FR-014), decided from the round's open bets alone so the decision
 * is the same on the provider's retry. Every open bet is settled: the win against the first, every other
 * as a loss, so a multi-debit round leaves nothing open in the wallet (the 2026-08-19 leak). A zero credit
 * settles every open bet as a loss. A round with no open bet receives the amount as a provider credit; a
 * round tagged with free rounds receives nothing, its win travelling on `round_settled` (FR-013).
 *
 * The key of each settling movement is `<credit transaction id>/<bet key>`, so each is idempotent on its
 * own and a repeated credit that reaches the wallet moves nothing.
 */
object Settlement:

  def credit(openBets: Vector[OpenBet], creditId: String, amount: Long, freeRounds: Boolean): Vector[WalletCall] =
    if freeRounds then Vector.empty
    else if openBets.isEmpty then (if amount > 0 then Vector(WalletCall.ProviderCredit(creditId, amount)) else Vector.empty)
    else if amount > 0 then
      val first +: rest = openBets: @unchecked
      WalletCall.Win(s"$creditId/${first.walletKey}", first.walletRoundId, amount, first.walletKey) +:
        rest.map(b => WalletCall.Loss(s"$creditId/${b.walletKey}", b.walletRoundId, b.walletKey))
    else openBets.map(b => WalletCall.Loss(s"$creditId/${b.walletKey}", b.walletRoundId, b.walletKey))
