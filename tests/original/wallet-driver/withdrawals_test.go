//go:build e2e

package walletdriver

import "testing"

// features/wallet/withdrawals.feature, scenario for scenario and every Examples row its own, at
// the original's withdrawal-request routes (the rebuild's hold, release, confirm and reverse).
// The original mints the request id; each scenario captures it as the feature's name for it.

func TestWithdrawals(t *testing.T) {
	sc := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	clear := WalletSetup{TurnoverMultiplier: ptr(int64(0))}

	sc("a hold takes the amount out of real balance and records the pending request", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Balance(), 200)
		s.Want(s.Transactions("?type=withdrawal"), 200)
	})
	sc("a second hold while a request is pending is refused", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 1000, 0), 201)
		s.Want(s.Hold("w2", 1000, 0), 409)
	})
	sc("a hold above real balance is refused", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(3000)
		s.Want(s.Hold("w1", 4000, 0), 409)
	})
	sc("a hold while deposit turnover is pending is refused", "002/FR-023", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: ptr(int64(0)), DepositTurnoverPending: 1})
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 409)
	})
	sc("a hold forfeits an award under a funds and winnings policy, applies its clawback once, and is refused when real balance is then short", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(5000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, ForfeitOnWithdrawal: "funds_and_winnings"}), 200)
		s.Want(s.Hold("w1", 4000, 1500), 409) // the original takes the clawback as input (FR-015a: the rebuild computes it)
		s.Want(s.Status(), 200)
	})
	sc("a hold forfeits a record under a funds policy without clawback, leaves a record under no policy untouched, and requires the player's confirmation", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, ForfeitOnWithdrawal: "funds", MaxConcurrentGrants: ptr(2)}), 200)
		s.Want(s.AwardBonus("B", Award{Amount: 1000, WageringMultiplier: 10, ForfeitOnWithdrawal: "none", MaxConcurrentGrants: ptr(2), Priority: ptr(1)}), 200)
		s.Want(s.Hold("w1", 4000, 700), 201)
		s.Want(s.Status(), 200)
	})
	sc("a hold forfeits a record under a cancel promotion policy and reports its award for cancellation", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.AwardBonus("A", Award{Amount: 1000, WageringMultiplier: 10, ForfeitOnWithdrawal: "cancel_promotion"}), 200)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Status(), 200)
	})
	sc("a hold draws down a converted record's cap, voids the excess and closes the record when its allowance is exhausted", "002/FR-023", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(8500)
		s.Want(s.AwardBonus("A", Award{Amount: 500, WageringMultiplier: 0, MaxWin: 2000}), 200)
		s.Want(s.Bet("r1", 100, BetOptions{}), 200) // converts: allowance 2000; real 8900
		s.Want(s.Deposit("p1", 1000), 200)          // deposits since conversion 1000; real 9900
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Status(), 200)
		s.Want(s.Transactions("?type=bonus_cap_void"), 200)
	})
	for _, outcome := range []string{"cancelled", "rejected", "failed"} {
		sc("a release returns the held amount and clears the pending request ("+outcome+")", "002/FR-024", func(s *Session) {
			s.NewWallet(clear)
			s.Funded(10000)
			s.Want(s.Hold("w1", 4000, 0), 201)
			s.Want(s.Release("w1", outcome, 4000), 200)
			s.Want(s.Balance(), 200)
			s.Want(s.Hold("w2", 100, 0), 201) // no request is pending
		})
	}
	sc("a confirmation clears the pending request with no money movement", "002/FR-024", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Complete("w1"), 200)
		s.Want(s.Balance(), 200)
		s.Want(s.Hold("w2", 100, 0), 201)
	})
	sc("a reversal after confirmation returns the amount", "002/FR-025", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Complete("w1"), 200)
		s.Want(s.Reverse("w1", 4000), 200)
		s.Want(s.Balance(), 200)
	})
	sc("a second reversal returns the first outcome", "002/FR-025", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Complete("w1"), 200)
		s.Want(s.Reverse("w1", 4000), 200)
		s.Reverse("w1", 4000)
		s.Want(s.Balance(), 200)
	})
	sc("a release or confirmation naming a request that is not the pending one is refused (released)", "002/FR-024", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Complete("w1"), 200)
		s.Want(s.Release("w1", "cancelled", 4000), 409)
	})
	sc("a release or confirmation naming a request that is not the pending one is refused (confirmed)", "002/FR-024", func(s *Session) {
		s.NewWallet(clear)
		s.Funded(10000)
		s.Want(s.Hold("w1", 4000, 0), 201)
		s.Want(s.Release("w1", "cancelled", 4000), 200)
		s.Want(s.Complete("w1"), 409)
	})
}
