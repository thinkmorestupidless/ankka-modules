//go:build e2e

package walletdriver

import "testing"

// features/wallet/restricted-funds.feature, scenario for scenario, and TestScenario22's funding
// resolver shapes. Records are created through the original's award route, never seeded, so every
// recording starts from what a caller can do.

func TestRestrictedFunds(t *testing.T) {
	sc := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	two := ptr(2)

	sc("an award creates an active restricted funds record with its wagering requirement", "002/FR-015", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Status(), 200)
		s.Want(s.Transactions("?type=bonus_credit"), 200)
	})
	sc("under real first funding with partial splits on, a bet real balance cannot cover alone is split", "002/FR-005", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, FundingOrder: "real_first"}), 200)
		s.Want(s.Bet("r1", 1500, BetOptions{}), 200)
		s.Want(s.Status(), 200)
	})
	skip(t, "under real first funding with partial splits off, a bet real balance cannot cover alone is funded from the record in full",
		"partial splits are the brand's switch, read by the original from casino-service's brand twin; the wallet's routes cannot turn it off for one scenario")
	sc("records fund a bet in priority order, the first drawn to zero before the next", "002/FR-020", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 300, WageringMultiplier: 10, Priority: ptr(0)}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 1000, WageringMultiplier: 10, Priority: ptr(1), MaxConcurrentGrants: two}), 200)
		s.Want(s.Bet("r1", 800, BetOptions{}), 200)
		s.Want(s.Status(), 200)
	})
	sc("a record whose release progress meets its wagering requirement converts on the bet that completes it", "002/FR-009", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(30000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, MaxWin: 5000, ContributionDefault: ptr(100)}), 200)
		s.Want(s.Bet("r1", 19500, BetOptions{}), 200) // progress to 19,500 from real
		s.Want(s.Bet("r2", 1000, BetOptions{}), 200)  // completes 20,000: converts
		s.Want(s.Status(), 200)
		s.Want(s.Balance(), 200)
	})
	sc("a win follows its source, with the last restricted funds leg absorbing the rounding remainder", "002/FR-011", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(500)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, WinAllocation: "follow_source"}), 200)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200) // 500 real, 500 record
		s.Want(s.Win("r1", 1001), 200)
		s.Want(s.Status(), 200)
	})
	sc("a win's share for a record that has since converted is paid to real balance", "002/FR-011", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 1, MaxWin: 5000}), 200)
		s.Want(s.Bet("r1", 1500, BetOptions{}), 200) // 1000 real + 500 record; progress 1500 ≥ 1000: converts
		s.Want(s.Win("r1", 1000), 200)
		s.Want(s.Balance(), 200)
	})
	sc("a bet on a game a record's scope excludes with violation mode block is refused", "002/FR-007", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, ScopeExclude: "game:slot-x|", ScopeViolationMode: "block"}), 200)
		s.Want(s.Bet("r1", 100, BetOptions{GameID: "slot-x"}), 409)
	})
	sc("a bet above a maximum stake enforced as cap is funded in full with its contribution base capped", "002/FR-007", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, MaxStake: ptr(int64(500)), MaxStakeEnforcement: "cap"}), 200)
		s.Want(s.Bet("r1", 800, BetOptions{}), 200)
		s.Want(s.Status(), 200) // wagered amount shows the capped base
	})
	sc("a bet above a maximum stake enforced as block is refused", "002/FR-007", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, MaxStake: ptr(int64(500)), MaxStakeEnforcement: "block"}), 200)
		s.Want(s.Bet("r1", 800, BetOptions{}), 409)
	})
	sc("a record at zero balance is forfeited when its last open round settles as a loss", "002/FR-013", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 500, WageringMultiplier: 10}), 200)
		s.Want(s.Bet("r1", 500, BetOptions{}), 200) // the record funds it all: balance 0, one open round
		s.Want(s.SettleLoss("r1"), 200)
		s.Want(s.Status(), 200)
	})
	sc("forfeiting an active record zeroes it and closes it as forfeited", "002/FR-016", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Forfeit(""), 200)
		s.Want(s.Status(), 200)
	})
	sc("forfeiting when no record is active is refused", "002/FR-016", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.Forfeit(""), 409)
	})
	sc("cancelling an award forfeits its record and applies the clawback clamped to real balance", "002/FR-017", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(300)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Cancel("", 500), 200) // the original takes the clawback as input; the rebuild computes it
		s.Want(s.Balance(), 200)
	})
	sc("cancelling with no active record still applies the clawback to real balance", "002/FR-017", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(300)
		s.Want(s.Cancel("", 500), 200)
		s.Want(s.Transactions("?type=bonus_cancel"), 200)
	})
	sc("closing the pending cap of a converted record closes it with no balance change", "002/FR-018", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(1000)
		s.Want(s.AwardBonus("A", Award{Amount: 500, WageringMultiplier: 0, MaxWin: 2000}), 200)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200) // a requirement of 0 converts on the first bet
		s.Want(s.ClosePendingCap(), 200)
		s.Want(s.Balance(), 200)
	})
	sc("closing a pending cap when no record awaits one is refused", "002/FR-018", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.ClosePendingCap(), 409)
	})
	sc("making another active award primary makes its record sort first on the next bet", "002/FR-019", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, Priority: ptr(0)}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 1000, WageringMultiplier: 10, Priority: ptr(1), MaxConcurrentGrants: two}), 200)
		s.Want(s.Switch("B"), 200)
		s.Want(s.Bet("r1", 300, BetOptions{}), 200)
		s.Want(s.Status(), 200)
	})
	sc("making an award that is not active primary is refused", "002/FR-019", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("C", Award{Amount: 1000, WageringMultiplier: 10}), 200)
		s.Want(s.Forfeit("C"), 200)
		s.Want(s.Switch("C"), 409)
	})
	sc("making an unknown award primary is refused", "002/FR-019", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.Switch("Z"), 404)
	})
	sc("a second award is refused when the brand allows one concurrent award", "002/FR-015", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, MaxConcurrentGrants: ptr(1)}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 1000, WageringMultiplier: 10, MaxConcurrentGrants: ptr(1)}), 409)
	})
	sc("a second award is accepted when the brand sets no limit on concurrent awards", "002/FR-015", func(s *Session) {
		// the original has no "no limit": the offer's own cap, here larger than any wallet holds
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, MaxConcurrentGrants: ptr(1000)}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 1000, WageringMultiplier: 10, MaxConcurrentGrants: ptr(1000)}), 200)
	})
	sc("a wallet with a restricted balance and no open record refuses every bet-path command", "002/FR-014", func(s *Session) {
		s.NewWallet(WalletSetup{RestrictedBalance: 500})
		s.Want(s.Bet("r1", 100, BetOptions{}), 409)
	})
}

// skip names a scenario the original cannot be driven to show from the wallet's routes, and why.
func skip(t *testing.T, name, reason string) {
	t.Run(name, func(t *testing.T) { t.Skip(reason) })
}

// TestScenario22_FundingResolver's shapes, at the wallet's routes.
func TestFundingResolverShapes(t *testing.T) {
	shape := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, "TestScenario22_FundingResolver_"+name, requirement, body) })
	}
	shape("real_only_legs_and_concept", "002/FR-010", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(5000)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
		s.Want(s.Transactions("?type=bet"), 200)
	})
	shape("bonus_active_conversion", "002/FR-009", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(5000)
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 2, MaxWin: 3000}), 200)
		s.Want(s.Bet("r1", 2000, BetOptions{}), 200)
		s.Want(s.Status(), 200)
	})
	shape("rules_by_seeding", "002/FR-008", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Funded(5000)
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, ContributionDefault: ptr(50)}), 200)
		s.Want(s.Bet("r1", 1000, BetOptions{GameID: "slot-a"}), 200)
		s.Want(s.Status(), 200)
	})
	shape("unmigrated_guard", "002/FR-014", func(s *Session) {
		s.NewWallet(WalletSetup{RestrictedBalance: 700})
		s.Want(s.Bet("r1", 100, BetOptions{}), 409)
		s.Want(s.Balance(), 200)
	})
	skip(t, "TestScenario22_FundingResolver_cutover_settlement",
		"settles a bet placed before the funding resolver's cutover (no funded_breakdown on the bet twin); only a seeded pre-cutover bet twin shows it, and the rebuild has no pre-cutover bets (spec 019 migrates open rounds as bets with legs)")
	shape("two_records_priority_order", "002/FR-020", func(s *Session) {
		s.NewWallet(WalletSetup{})
		s.Want(s.AwardBonus("A", Award{Amount: 400, WageringMultiplier: 10, Priority: ptr(1)}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 400, WageringMultiplier: 10, Priority: ptr(0), MaxConcurrentGrants: ptr(2)}), 200)
		s.Want(s.Bet("r1", 600, BetOptions{}), 200)
		s.Want(s.Status(), 200)
	})
}
