//go:build e2e

package walletdriver

import "testing"

// features/wallet/reads.feature, scenario for scenario: balance, bonus status (the rebuild's
// restricted-funds status), transactions, transaction-sum and lifetime-stats. The original answers
// transactions and sums from substrate-graphql's projection, which lags its writes; each read
// follows its writes in the same scenario, so a replay compares the settled answer.

func TestReads(t *testing.T) {
	sc := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	clear := WalletSetup{TurnoverMultiplier: ptr(int64(0))}

	sc("a balance read returns real balance, restricted balance, currency and the primary record's progress", "002/FR-028", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Bet("r1", 500, BetOptions{}), 200)
		s.Want(s.Balance(), 200)
	})
	sc("a restricted funds status read lists every open record in order with the primary record's view as the headline", "002/FR-028", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, Priority: ptr(1), ForfeitOnWithdrawal: "funds"}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 500, WageringMultiplier: 5, Priority: ptr(0), MaxWin: 2000, MaxConcurrentGrants: ptr(2)}), 200)
		s.Want(s.Status(), 200)
	})
	sc("transactions are listed by type, wallet type, date range and page with every field of the row", "002/FR-029", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(5000)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
		s.Want(s.Win("r1", 1500), 200)
		s.Want(s.Adjust("credit", "real", 100, "goodwill"), 200)
		s.Want(s.Transactions(""), 200)
		s.Want(s.Transactions("?type=bet&walletType=real&page=1&pageSize=1"), 200)
		s.Want(s.Transactions("?from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&page=2&pageSize=2"), 200)
	})
	sc("a sum by type over a date range returns the total and the count, and lifetime totals report deposited, wagered and won", "002/FR-029", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(5000)
		s.Want(s.Deposit("p2", 2500), 200)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
		s.Want(s.Win("r1", 300), 200)
		s.Want(s.TransactionSum("?type=deposit&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z"), 200)
		s.Want(s.LifetimeStats(), 200)
	})
}
