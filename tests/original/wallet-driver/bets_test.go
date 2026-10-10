//go:build e2e

package walletdriver

import "testing"

// features/wallet/bets.feature, scenario for scenario, and the St8 wallet shapes spec 002 records
// (TestScenario15_St8Wallet_*), driven at the wallet's own routes rather than through the adapter.

func TestBets(t *testing.T) {
	t.Run("real leg", func(t *testing.T) {
		run(t, "a bet funded from real balance alone opens its round with one real leg", "002/FR-005", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Funded(10000)
			s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
			s.Want(s.Balance(), 200)
		})
	})
	t.Run("win", func(t *testing.T) {
		run(t, "a win settles the bet and closes the round", "002/FR-011", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Funded(10000)
			s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
			s.Want(s.Win("r1", 2500), 200)
			// a second settlement finds no open bet: the round is closed
			s.Want(s.SettleLoss("r1"), 404)
		})
	})
	t.Run("rollback", func(t *testing.T) {
		run(t, "a rollback restores the stake, voids the bet and closes the round", "002/FR-012", func(s *Session) {
			s.NewWallet(WalletSetup{DepositTurnoverPending: 5000})
			s.Funded(10000)
			s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
			s.Want(s.Rollback("r1"), 200)
			s.Want(s.SettleLoss("r1"), 404)
			s.Want(s.Hold("w1", 100, 0), 409) // turnover pending is not restored: still blocked
		})
	})
	t.Run("loss", func(t *testing.T) {
		run(t, "settling a round as a loss moves no money and closes the round", "002/FR-013", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Funded(10000)
			s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
			s.Want(s.SettleLoss("r1"), 200)
			s.Want(s.Balance(), 200)
			s.Want(s.Win("r1", 100), 404)
		})
	})
	t.Run("insufficient", func(t *testing.T) {
		run(t, "a bet the wallet cannot fund is refused and opens no round", "002/FR-006", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Funded(500)
			s.Want(s.Bet("r1", 1000, BetOptions{}), 409)
			s.Want(s.Win("r1", 100), 404)
			s.Want(s.Transactions("?type=bet"), 200)
		})
	})
	t.Run("correction debit", func(t *testing.T) {
		run(t, "a correction debit that allows a negative balance is funded from real balance with no contribution", "002/FR-006", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Want(s.Bet("r1", 1000, BetOptions{AllowNegative: true}), 200)
			s.Want(s.Balance(), 200)
		})
	})
	t.Run("large win", func(t *testing.T) {
		run(t, "a large win on a large stake is credited exactly", "002/SC-004", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Funded(50000)
			s.Want(s.Bet("r7", 50000, BetOptions{}), 200)
			s.Want(s.Win("r7", 500000), 200)
			s.Want(s.Balance(), 200)
		})
	})
	t.Run("win twice", func(t *testing.T) {
		run(t, "a win credited twice under the same key returns the first balances and writes nothing", "002/FR-002", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Funded(10000)
			s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
			s.Want(s.Win("r1", 2500), 200)
			// The original keys a win by its round: the second finds the bet settled.
			s.Win("r1", 2500)
			s.Want(s.Balance(), 200)
		})
	})
	t.Run("win without bet", func(t *testing.T) {
		run(t, "a win for a round with no open bet is refused", "002/FR-011", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Want(s.Win("r9", 100), 404)
		})
	})
	t.Run("zero bet", func(t *testing.T) {
		run(t, "a bet of zero is accepted, funds nothing and opens its round", "002/FR-010", func(s *Session) {
			s.NewWallet(WalletSetup{})
			s.Want(s.Bet("r0", 0, BetOptions{}), 200)
			s.Want(s.SettleLoss("r0"), 200)
		})
	})
}

// The ST8 adapter's wallet shapes (TestScenario15_St8Wallet_*), as the calls the adapter makes on
// the wallet. Shapes that exist only in the adapter (signatures, tokens, its own idempotency
// store, parameter mapping) are the game-provider's to record (spec 003).
func TestSt8WalletShapes(t *testing.T) {
	shape := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, "TestScenario15_St8Wallet_"+name, requirement, body) })
	}
	shape("BasicBalance", "002/FR-028", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Balance(), 200)
	})
	shape("BasicDebit", "002/FR-005", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{GameID: "slot-a", Provider: "st8"}), 200)
	})
	shape("BasicCredit", "002/FR-011", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Win("r1", 250), 200)
	})
	shape("BasicCancel", "002/FR-012", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Rollback("r1"), 200)
	})
	shape("ZeroDebit", "002/FR-010", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 0, BetOptions{}), 200)
	})
	shape("NotEnoughMoney", "002/FR-006", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(100)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 409)
	})
	shape("LargeWinOnLargeStake", "002/SC-004", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(500000)
		s.Want(s.Bet("r1", 500000, BetOptions{}), 200)
		s.Want(s.Win("r1", 5000000), 200)
	})
	shape("IdempotentDebit", "002/FR-002", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		// the original has no key: the same bet again debits again (spec 001 known defects)
		s.Bet("r1", 100, BetOptions{})
		s.Want(s.Balance(), 200)
	})
	shape("IdempotentCredit", "002/FR-002", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Win("r1", 250), 200)
		s.Win("r1", 250)
		s.Want(s.Balance(), 200)
	})
	shape("IdempotentCancel", "002/FR-002", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Rollback("r1"), 200)
		s.Rollback("r1")
		s.Want(s.Balance(), 200)
	})
	shape("CorrectionDebitNegativeBalance", "002/FR-006", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(100)
		s.Want(s.Bet("r1", 1000, BetOptions{AllowNegative: true}), 200)
		s.Want(s.Balance(), 200)
	})
	shape("BigRoundMultipleDebits", "002/FR-010", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Bet("r1", 200, BetOptions{})
		s.Bet("r1", 300, BetOptions{})
		s.Want(s.Balance(), 200)
	})
	shape("BigRoundMultipleCredits", "002/FR-011", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Win("r1", 100)
		s.Win("r1", 200)
		s.Want(s.Balance(), 200)
	})
	shape("BigRoundMultipleCancels1", "002/FR-012", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Bet("r1", 200, BetOptions{})
		s.Rollback("r1")
		s.Rollback("r1")
		s.Want(s.Balance(), 200)
	})
	shape("BigRoundMultipleCancels2", "002/FR-012", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Bet("r2", 200, BetOptions{}), 200)
		s.Want(s.Rollback("r2"), 200)
		s.Want(s.Rollback("r1"), 200)
		s.Want(s.Balance(), 200)
	})
	shape("Resettlement", "002/FR-012", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Win("r1", 300), 200)
		// a rollback after the round settled restores the stake (spec Edge Cases)
		s.Rollback("r1")
		s.Want(s.Balance(), 200)
	})
	shape("CancelEverythingWithinRound", "002/FR-012", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(10000)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200)
		s.Want(s.Win("r1", 300), 200)
		s.Rollback("r1")
		// the provider's correction debit reverses the win
		s.Want(s.Bet("r1-correction", 300, BetOptions{AllowNegative: true}), 200)
		s.Want(s.Balance(), 200)
	})
}
