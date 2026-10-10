//go:build e2e

package walletdriver

import "testing"

// features/wallet/deposits.feature, scenario for scenario, at the internal deposit route merchant
// calls. The brand's turnover multiplier is the original's sub-brand twin, shared by every player
// of the brand; a scenario sets the player's override instead (turnover_multiplier_override, which
// the original reads before the brand's), so no scenario changes the brand under another.

func TestDeposits(t *testing.T) {
	sc := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	one, zero := ptr(int64(1)), ptr(int64(0))

	sc("a confirmed deposit credits real balance and records its required turnover", "002/FR-022", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: one})
		s.Want(s.Deposit("p1", 5000), 200)
		s.Want(s.Balance(), 200)
		s.Want(s.ObserveWallet(), 200)
	})
	sc("a deposit confirmed again under the same payment reference moves nothing", "002/FR-002", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: one})
		s.Want(s.Deposit("p1", 5000), 200)
		s.Want(s.Deposit("p1", 5000), 200) // the original answers the balances of the moment, txnId ""
		s.Want(s.Transactions("?type=deposit"), 200)
	})
	sc("a deposit with a turnover multiplier of zero is cleared at once", "002/FR-022", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: zero})
		s.Want(s.Deposit("p2", 5000), 200)
		s.Want(s.ObserveWallet(), 200)
	})
	sc("a bet's real leg reduces deposit turnover pending", "002/FR-010", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: zero, DepositTurnoverPending: 5000})
		s.Funded(5000)
		s.Want(s.Bet("r1", 2000, BetOptions{}), 200)
		s.Want(s.ObserveWallet(), 200)
	})
	sc("a bet funded only from restricted funds leaves deposit turnover pending unchanged", "002/FR-010", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: zero, DepositTurnoverPending: 5000})
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10}), 200)
		s.Want(s.Bet("r1", 500, BetOptions{}), 200)
		s.Want(s.ObserveWallet(), 200)
	})
	sc("a rollback does not restore deposit turnover pending", "002/FR-012", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: zero, DepositTurnoverPending: 5000})
		s.Funded(5000)
		s.Want(s.Bet("r1", 2000, BetOptions{}), 200)
		s.Want(s.Rollback("r1"), 200)
		s.Want(s.ObserveWallet(), 200)
	})
	sc("a deposit while a converted record awaits its cap advances that record's deposits since conversion", "002/FR-022", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: zero})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 500, WageringMultiplier: 0, MaxWin: 2000}), 200)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200) // converts: requirement 0
		s.Want(s.Deposit("p3", 1000), 200)
		s.Want(s.Status(), 200) // restrictedFunds[].depositsSinceRelease
	})
	sc("a first deposit in a currency creates the wallet for that currency", "002/FR-001", func(s *Session) {
		// The original refuses WALLET_NOT_FOUND: its wallets are created by casino-service at
		// registration, never by a deposit. Recorded as the original answers; the rebuild creates
		// the wallet (FR-001), a divergence the parity test names.
		s.Currency = "USD"
		s.Want(s.Deposit("p4", 1000), 404)
	})
}
