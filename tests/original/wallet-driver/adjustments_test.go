//go:build e2e

package walletdriver

import "testing"

// features/wallet/adjustments.feature, scenario for scenario, at the original's admin-adjust and
// award (provider credit) routes, and the provider-credit and bonus-adjustment shapes spec 002
// records (TestScenario15_St8Wallet_StandaloneCredit, TestScenario245_*).

func TestAdjustments(t *testing.T) {
	sc := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	clear := WalletSetup{TurnoverMultiplier: ptr(int64(0))}

	sc("an operator's credit to real balance carries its reason and actor", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(1000)
		s.Want(s.Adjust("credit", "real", 500, "goodwill"), 200)
		s.Want(s.Transactions("?type=admin_adjust"), 200)
	})
	sc("an operator's debit above real balance is refused naming both amounts", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(1000)
		s.Want(s.Adjust("debit", "real", 1500, "correction"), 400)
	})
	sc("an adjustment without a reason is refused", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(1000)
		s.Want(s.Adjust("credit", "real", 500, ""), 400)
	})
	sc("an operator's debit that leaves a record at zero with no open round forfeits it", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.AwardBonus("A", Award{Amount: 800, WageringMultiplier: 10}), 200)
		s.Want(s.Adjust("debit", "bonus", 800, "correction"), 200)
		s.Want(s.Status(), 200)
	})
	sc("an operator's debit that leaves a record above zero leaves it active", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.AwardBonus("A", Award{Amount: 800, WageringMultiplier: 10}), 200)
		s.Want(s.Adjust("debit", "bonus", 300, "correction"), 200)
		s.Want(s.Status(), 200)
	})
	sc("an adjustment to restricted funds with no active record is refused", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.Adjust("credit", "bonus", 500, "goodwill"), 409)
	})
	sc("a provider credit lands on the primary active record", "002/FR-027", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.AwardBonus("A", Award{Amount: 800, WageringMultiplier: 10}), 200)
		s.Want(s.ProviderCredit("c1", 700), 200)
		s.Want(s.Status(), 200)
	})
	sc("a provider credit with no active record lands on real balance", "002/FR-027", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(1000)
		s.Want(s.ProviderCredit("c1", 700), 200)
		s.Want(s.Balance(), 200)
	})
	sc("an adjustment is never counted as a deposit", "002/FR-026", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.Adjust("credit", "real", 500, "goodwill"), 200)
		s.Want(s.TransactionSum("?type=deposit"), 200)
		s.Want(s.LifetimeStats(), 200)
	})
}

func TestAdjustmentShapes(t *testing.T) {
	shape := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	shape("TestScenario15_St8Wallet_StandaloneCredit", "002/FR-027", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.ProviderCredit("credit", 250), 200)
		s.Want(s.Balance(), 200)
	})
	shape("TestScenario245_ZeroedBonusClosesRecordAndGrant", "002/FR-026", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10}), 200)
		s.Want(s.Adjust("debit", "bonus", 1000, "zeroed"), 200)
		s.Want(s.Status(), 200)
	})
	shape("TestScenario245_PartialBonusAdjustLeavesRecordActive", "002/FR-026", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10}), 200)
		s.Want(s.Adjust("debit", "bonus", 400, "partial"), 200)
		s.Want(s.Status(), 200)
	})
}
