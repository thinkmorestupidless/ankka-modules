//go:build e2e

package walletdriver

import "testing"

// features/wallet/pools.feature (spec 025), scenario for scenario, at the original's wallet
// routes. The starting pools are made by the original's own rules (StartingPools: a conversion for
// the converted pool, a deposit for the deposit pool), and every scenario reads the balance after
// each step, which checks that the pools sum to the real balance; "the two pools always sum to the
// real balance" is asserted that way rather than as a scenario of its own. Not here: the fact
// scenario (the rebuild's topic) and the two migration scenarios (spec 019). A scenario whose answer
// differs from the feature is still recorded: the recording is the original's behaviour, and the
// difference is a finding for the spec.

func TestPools(t *testing.T) {
	sc := func(name, requirement string, body func(s *Session)) {
		t.Run(name, func(t *testing.T) { run(t, name, requirement, body) })
	}
	clear := WalletSetup{TurnoverMultiplier: ptr(int64(0))}

	// User Story 1: a stake and its win are split between the pools

	sc("the real leg of a stake is taken from the converted pool first", "025/FR-003", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(5000, 5000)
		bet := s.Bet("r1", 1000, BetOptions{})
		s.Want(bet, 200)
		s.DepositPart(bet, "fundedDepositAmount", 0)
		s.PoolsAre(5000, 4000)
	})
	sc("a stake larger than the converted pool takes the remainder from the deposit pool", "025/FR-003", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(5000, 5000)
		bet := s.Bet("r1", 6000, BetOptions{})
		s.Want(bet, 200)
		s.DepositPart(bet, "fundedDepositAmount", 1000)
		s.PoolsAre(4000, 0)
	})
	sc("a stake funded only from restricted funds moves neither pool", "025/FR-003", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(5000, 0)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10, FundingOrder: "bonus_first"}), 200)
		bet := s.Bet("r1", 1000, BetOptions{})
		s.Want(bet, 200)
		s.DepositPart(bet, "fundedDepositAmount", 0)
		s.PoolsAre(5000, 0)
	})
	sc("a conversion joins the converted pool before the stake's real leg is split", "025/FR-003", func(s *Session) {
		// the feature's record (3000, requirement 20000, progress 19500) is reached here by a record
		// with no requirement, which converts on the bet it first sees: the same order of the steps
		s.NewWallet(clear)
		s.StartingPools(1000, 0)
		s.Want(s.AwardBonus("A", Award{Amount: 3000, WageringMultiplier: 0, MaxWin: 3000, FundingOrder: "real_first"}), 200)
		bet := s.Bet("r1", 1000, BetOptions{})
		s.Want(bet, 200)
		s.DepositPart(bet, "fundedDepositAmount", 0)
		s.PoolsAre(1000, 2000)
	})
	sc("a win is split in the stake's ratio with the deposit part rounded down", "025/FR-004", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(1000, 5000)
		s.Want(s.Bet("r1", 6000, BetOptions{}), 200)
		d, c := s.Pools()
		win := s.Win("r1", 1000)
		s.Want(win, 200)
		s.DepositPart(win, "wonDepositAmount", 166)
		s.PoolsRose(d, c, 166, 834)
	})
	sc("a win on a stake with no real leg moves neither pool while its record is active", "025/FR-004", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
		s.Want(s.Win("r1", 2000), 200)
		s.PoolsAre(0, 0)
		s.Want(s.Status(), 200)
	})
	sc("a win's share for a record no longer active goes to the converted pool", "025/FR-004", func(s *Session) {
		s.NewWallet(clear)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Bet("r1", 1000, BetOptions{}), 200)
		s.Want(s.Forfeit(""), 200)
		win := s.Win("r1", 2000)
		s.Want(win, 200)
		s.DepositPart(win, "wonDepositAmount", 0)
		s.PoolsAre(0, 2000)
	})
	sc("a rollback restores the stake's recorded parts after later movements changed the pools", "025/FR-005", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(1000, 5000)
		s.Want(s.Bet("r1", 6000, BetOptions{}), 200)
		s.Want(s.Deposit("p1", 9000), 200)
		d, c := s.Pools()
		s.Want(s.Rollback("r1"), 200)
		s.PoolsRose(d, c, 1000, 5000)
	})
	sc("settling a round as a loss moves neither pool", "025/FR-006", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(1000, 5000)
		s.Want(s.Bet("r1", 6000, BetOptions{}), 200)
		d, c := s.Pools()
		s.Want(s.SettleLoss("r1"), 200)
		s.PoolsAre(d, c)
	})
	sc("a correction debit that takes real balance negative charges the shortfall to the converted pool", "025/FR-003", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(300, 200)
		debit := s.Shortfall("r1", 1000)
		s.Want(debit, 200)
		s.DepositPart(debit, "fundedDepositAmount", 300)
		s.PoolsAre(0, -500)
	})
	sc("a deposit joins the deposit pool", "025/FR-007", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(0, 2000)
		s.Want(s.Deposit("p1", 5000), 200)
		s.PoolsAre(5000, 2000)
	})
	sc("only the deposit part of a stake reduces deposit turnover pending", "025/FR-003a", func(s *Session) {
		s.NewWallet(WalletSetup{TurnoverMultiplier: ptr(int64(0)), DepositTurnoverPending: 20000})
		s.StartingPools(5000, 5000)
		bet := s.Bet("r1", 6000, BetOptions{})
		s.Want(bet, 200)
		s.DepositPart(bet, "fundedDepositAmount", 1000)
		s.Want(s.ObserveWallet(), 200) // deposit turnover pending: on the twin only
		s.PoolsAre(4000, 0)
	})

	// User Story 2: holds, adjustments and credits move the pools by rule

	sc("a hold takes the paid amount from the deposit pool first", "025/FR-008", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(5000, 5000)
		s.Want(s.Hold("w1", 6000, 0), 201)
		s.PoolsAre(0, 4000)
	})
	skip(t, "a hold's cap void and clawback come from the converted pool first, before the paid amount",
		"the feature's starting state (a record awaiting its cap with an allowance of 2000 beside a converted pool of 3000, and an active record with a computed clawback of 1500) is not reachable from the wallet's routes with these figures; withdrawals_test's cap scenario records the cap void, and its replay now reads the pools")
	sc("a release returns the hold's parts to the pools they were taken from", "025/FR-008", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(5000, 5000)
		s.Want(s.Hold("w1", 6000, 0), 201)
		s.PoolsAre(0, 4000)
		s.Want(s.Release("w1", "cancelled", 6000), 200)
		s.PoolsAre(5000, 5000)
	})
	sc("a reversal returns the hold's parts to the pools they were taken from", "025/FR-008", func(s *Session) {
		// a hold with nothing voided (research R11)
		s.NewWallet(clear)
		s.StartingPools(5000, 5000)
		s.Want(s.Hold("w1", 6000, 0), 201)
		s.Want(s.Complete("w1"), 200)
		d, c := s.Pools()
		s.Want(s.Reverse("w1", 6000), 200)
		s.PoolsRose(d, c, 5000, 1000)
	})
	sc("an operator's credit joins the deposit pool", "025/FR-010", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(1000, 1000)
		s.Want(s.Adjust("credit", "real", 500, "goodwill"), 200)
		s.PoolsAre(1500, 1000)
	})
	sc("an operator's debit comes from the deposit pool first", "025/FR-010", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(1000, 1000)
		s.Want(s.Adjust("debit", "real", 1500, "correction"), 200)
		s.PoolsAre(0, 500)
	})
	sc("a provider credit to real balance joins the converted pool", "025/FR-010", func(s *Session) {
		s.NewWallet(clear)
		s.StartingPools(1000, 0)
		s.Want(s.ProviderCredit("pc1", 700), 200)
		s.PoolsAre(1000, 700)
	})
	sc("cancelling an award takes its clawback from the converted pool first", "025/FR-009", func(s *Session) {
		// the original takes the clawback as input (spec 002 FR-015a: the rebuild computes it)
		s.NewWallet(clear)
		s.StartingPools(1000, 1000)
		s.Want(s.AwardBonus("A", Award{Amount: 2000, WageringMultiplier: 10}), 200)
		s.Want(s.Cancel("", 1500), 200)
		s.PoolsAre(500, 0)
	})

	// User Story 3: the pools are read

	sc("the balance read reports both pools beside the real balance", "025/FR-013", func(s *Session) {
		// the wallet's own balance route; the operator's and the player's need the original's
		// back-office and player tokens, which the driver does not mint (the original's
		// s253Pools reads the player's route with a verified login)
		s.NewWallet(clear)
		s.StartingPools(5000, 3000)
		s.PoolsAre(5000, 3000)
	})
}
