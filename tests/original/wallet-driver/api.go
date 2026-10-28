package walletdriver

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// The original's wallet API, one helper per route the scenarios use. Amounts are given in minor
// units and sent as the original expects them: major-unit decimal strings on deposit, bet, win,
// award and create-withdrawal-request; cents as numbers on the withdrawal lifecycle routes, which
// read the amount off the withdrawal-request twin (WalletRoutes.scala, "Do NOT re-multiply").

// WalletSetup is what casino-service's createWalletTwin seeds, plus the columns a scenario needs
// in place before its first step.
type WalletSetup struct {
	RestrictedBalance      int64  // a stray balance with no record (the unmigrated state)
	DepositTurnoverPending int64  // turnover still to wager
	TurnoverMultiplier     *int64 // the player's turnover_multiplier_override (the brand's terms stand in)
}

// NewWallet creates the player and wallet twins as casino-service does at registration. The
// original's deposit refuses WALLET_NOT_FOUND without them; the rebuild creates a wallet on its
// first deposit or award instead (spec 002 FR-001).
func (s *Session) NewWallet(w WalletSetup) {
	brand := s.cfg.Brand
	// the original derives the wallet twin's id from brand, player and currency; recorded as a
	// placeholder, since a replay's player differs
	s.ids["walletTwin"] = WalletTwinID(brand, s.Player, s.Currency)
	s.order = append(s.order, "walletTwin")
	player := map[string]any{"player_id": s.Player, "brand_id": brand}
	if w.TurnoverMultiplier != nil {
		player["turnover_multiplier_override"] = *w.TurnoverMultiplier
		player["turnover_definition_override"] = "gross_wagered"
	}
	s.Setup("player twin", http.MethodPost, fmt.Sprintf("/twins/casino-%s/%s", brand, s.Player), player)
	props := map[string]any{
		"real_balance":             0,
		"restricted_balance":       w.RestrictedBalance,
		"deposit_turnover_pending": w.DepositTurnoverPending,
		"currency":                 s.Currency,
		"brand_id":                 brand,
		"player_id":                s.Player,
		"brand_ref":                brand,
		"player_ref":               s.Player,
		"processed_payment_refs":   "||",
		// spec 025: casino-service's createWalletTwin stamps a new wallet's pools at 0 / 0
		// (SubstrateTwinClient.scala), so every wallet the driver makes is stamped
		"deposit_pool_balance":   0,
		"converted_pool_balance": 0,
		"pools_stamped_at":       "2026-01-01T00:00:00Z",
	}
	s.Setup("wallet twin", http.MethodPost, "/twins/wallet/"+s.ids["walletTwin"], props)
	s.Setup("wallet concept", http.MethodPost, "/twins/wallet/"+s.ids["walletTwin"]+"/concept", map[string]any{"conceptId": "wallet"})
	s.Setup("player→wallet", http.MethodPost, fmt.Sprintf("/twins/casino-%s/%s/edges", brand, s.Player),
		map[string]any{"edgeType": "player→wallet", "targetTwinId": "wallet/" + s.ids["walletTwin"]})
}

// Deposit confirms a deposit as merchant does (the internal deposit route).
func (s *Session) Deposit(ref string, amount int64) Response {
	return s.Post("deposit", s.Wallet("deposit"), map[string]any{
		"paymentRef": s.ID(ref), "amount": Decimal(amount), "terminalId": "t1", "pspId": "dreamzpay", "scheme": "card",
	})
}

// Funded deposits amount and expects it credited: the usual first step of a scenario.
func (s *Session) Funded(amount int64) {
	if amount > 0 {
		s.Want(s.Deposit("fund", amount), 200)
	}
}

// BetOptions are a bet's optional fields.
type BetOptions struct {
	GameID, Provider, GameType string
	AllowNegative              bool
}

// Bet places a bet; the round is a minted id named `round`.
func (s *Session) Bet(round string, amount int64, o BetOptions) Response {
	body := map[string]any{"roundId": s.ID(round), "amount": Decimal(amount), "sessionId": s.ID("session")}
	if o.GameID != "" {
		body["gameId"] = o.GameID
	}
	if o.Provider != "" {
		body["provider"] = o.Provider
	}
	if o.GameType != "" {
		body["gameType"] = o.GameType
	}
	if o.AllowNegative {
		body["allowNegative"] = true
	}
	return s.Post("bet", s.Wallet("bet"), body)
}

func (s *Session) Win(round string, amount int64) Response {
	return s.Post("win", s.Wallet("win"), map[string]any{"roundId": s.ID(round), "amount": Decimal(amount)})
}

func (s *Session) Rollback(round string) Response {
	return s.Post("rollback", s.Wallet("rollback"), map[string]any{"roundId": s.ID(round)})
}

func (s *Session) SettleLoss(round string) Response {
	return s.Post("settle loss", s.Wallet("settle-loss"), map[string]any{"roundId": s.ID(round)})
}

func (s *Session) Balance() Response { return s.Get("balance", s.Wallet("balance")) }

func (s *Session) Status() Response { return s.Get("bonus status", s.Wallet("bonus/status")) }

// Award is the rule snapshot an award carries (BonusCreditRequest; maxStake in cents).
type Award struct {
	Amount              int64
	WageringMultiplier  int
	MaxWin              int64 // the maximum convertible amount
	Priority            *int
	MaxStake            *int64
	MaxStakeEnforcement string
	ContributionDefault *int
	ScopeExclude        string
	ScopeViolationMode  string
	FundingOrder        string
	WinAllocation       string
	ForfeitOnWithdrawal string
	MaxConcurrentGrants *int
}

// Grant is the grant reference the session minted for an award name, in the original's shape.
func (s *Session) Grant(name string) string { return "bonus-claims/" + s.UUID(name) }

// AwardBonus awards restricted funds under the grant `name`.
func (s *Session) AwardBonus(name string, a Award) Response {
	body := map[string]any{
		"bonusRef":           s.ID("bonus-" + name),
		"amount":             Decimal(a.Amount),
		"wageringMultiplier": a.WageringMultiplier,
		"operatorId":         "operator-1",
		"grantRef":           s.Grant(name),
	}
	if a.MaxWin > 0 {
		body["maxWin"] = Decimal(a.MaxWin)
	}
	opt := func(k string, v any, set bool) {
		if set {
			body[k] = v
		}
	}
	if a.Priority != nil {
		body["priority"] = *a.Priority
	}
	if a.MaxStake != nil {
		body["maxStake"] = *a.MaxStake
	}
	if a.ContributionDefault != nil {
		body["contributionDefault"] = *a.ContributionDefault
	}
	if a.MaxConcurrentGrants != nil {
		body["maxConcurrentGrants"] = *a.MaxConcurrentGrants
	}
	opt("maxStakeEnforcement", a.MaxStakeEnforcement, a.MaxStakeEnforcement != "")
	opt("scopeExclude", a.ScopeExclude, a.ScopeExclude != "")
	opt("scopeViolationMode", a.ScopeViolationMode, a.ScopeViolationMode != "")
	opt("fundingOrder", a.FundingOrder, a.FundingOrder != "")
	opt("winAllocation", a.WinAllocation, a.WinAllocation != "")
	opt("forfeitOnWithdrawal", a.ForfeitOnWithdrawal, a.ForfeitOnWithdrawal != "")
	return s.Post("award", s.Wallet("bonus/award"), body)
}

// Forfeit forfeits the primary record, or the named grant's when name is not "".
func (s *Session) Forfeit(name string) Response {
	body := map[string]any{}
	if name != "" {
		body["grant_ref"] = s.Grant(name)
	}
	return s.Post("forfeit", s.Wallet("bonus/forfeit"), body)
}

// Cancel cancels with the clawback the original takes as input (the rebuild computes it, FR-015a).
func (s *Session) Cancel(name string, clawback int64) Response {
	body := map[string]any{"clawbackAmount": Decimal(clawback)}
	if name != "" {
		body["grantRef"] = s.Grant(name)
	}
	return s.Post("cancel bonus", s.Wallet("cancel-bonus"), body)
}

func (s *Session) ClosePendingCap() Response {
	return s.Post("close pending cap", s.Wallet("bonus/close-pending-cap"), map[string]any{})
}

func (s *Session) Switch(name string) Response {
	return s.Post("switch", s.Wallet("bonus/switch"), map[string]any{"grantRef": s.Grant(name)})
}

// ProviderCredit is the original's `award` route: a game provider's standalone credit.
func (s *Session) ProviderCredit(ref string, amount int64) Response {
	return s.Post("provider credit", s.Wallet("award"), map[string]any{"paymentRef": s.ID(ref), "amount": Decimal(amount)})
}

// Adjust is an operator's adjustment: kind credit|debit, target real|bonus.
func (s *Session) Adjust(kind, target string, amount int64, reason string) Response {
	return s.Post("admin adjust", s.Wallet("admin-adjust"), map[string]any{
		"type": kind, "wallet_type": target, "amount": Decimal(amount), "reason": reason, "actor": "operator-1",
	})
}

// Hold creates a withdrawal request and captures the id the original minted under `name`.
func (s *Session) Hold(name string, amount, clawback int64) Response {
	r := s.Post("create withdrawal request", s.Wallet("create-withdrawal-request"), map[string]any{
		"amount": Decimal(amount), "clawback_amount": Decimal(clawback), "terminal_id": "t1", "psp_id": "dreamzpay",
		"destination_id": s.ID("destination"), "wallet_ref": "wallet/" + s.ids["walletTwin"],
		"player_ref": s.Player, "brand_ref": s.cfg.Brand,
	})
	if r.Status == 201 {
		s.Capture(name, "$.requestId", r.Str("requestId"))
	}
	return r
}

// Release ends a pending request as cancelled, rejected or failed (cents on these routes).
func (s *Session) Release(name, outcome string, amount int64) Response {
	verb := map[string]string{"cancelled": "cancel", "rejected": "reject", "failed": "fail"}[outcome]
	body := map[string]any{"request_id": s.ids[name], "amount": amount, "currency": s.Currency, "status": "PENDING_REVIEW"}
	if outcome != "cancelled" {
		body["failure_reason"] = "driver"
		body["resolved_by"] = "operator-1"
	}
	return s.Post(outcome+" withdrawal request", s.Wallet(verb+"-withdrawal-request"), body)
}

func (s *Session) Complete(name string) Response {
	return s.Post("complete withdrawal request", s.Wallet("complete-withdrawal-request"), map[string]any{"request_id": s.ids[name]})
}

func (s *Session) Reverse(name string, amount int64) Response {
	return s.Post("reverse withdrawal request", s.Wallet("reverse-withdrawal-request"), map[string]any{
		"request_id": s.ids[name], "current_request_status": "COMPLETED", "amount": amount, "currency": s.Currency,
	})
}

func (s *Session) Transactions(query string) Response {
	return s.Get("transactions", s.Wallet("transactions")+query)
}

func (s *Session) LifetimeStats() Response {
	return s.Get("lifetime stats", s.Wallet("lifetime-stats"))
}

func (s *Session) TransactionSum(query string) Response {
	return s.Get("transaction sum", s.Wallet("transaction-sum")+query)
}

// ObserveWallet reads the wallet twin from substrate: the original has no route that answers
// deposit turnover pending, so a scenario that shows it observes the twin. Recorded as an
// `observe:` step; the rebuild's parity test reads the same figure from the entity.
func (s *Session) ObserveWallet() Response {
	return s.call("observe: wallet twin", "substrate", http.MethodGet, s.cfg.SubstrateURL, "/twins/wallet/"+s.ids["walletTwin"], nil, false)
}

// Spec 025's pools. The original serves them on the balance route as major strings
// (`depositPoolBalance`, `convertedPoolBalance`), a bet's deposit part as `fundedDepositAmount` and a
// win's as `wonDepositAmount`. A transaction's parts (`funded_*`, `won_*`, `from_*`, `to_*`) are on
// its twin only: the transactions route does not serve them.

// Minor reads a field the original wrote in major units ("50.00") as minor units; ok is false
// when the field is absent or not a number.
func (r Response) Minor(field string) (int64, bool) {
	text := r.Str(field)
	if text == "" {
		return 0, false
	}
	negative := strings.HasPrefix(text, "-")
	whole, frac, _ := strings.Cut(strings.TrimPrefix(text, "-"), ".")
	w, err := strconv.ParseInt(whole, 10, 64)
	if err != nil {
		return 0, false
	}
	f, err := strconv.ParseInt((frac + "00")[:2], 10, 64)
	if err != nil {
		return 0, false
	}
	if negative {
		return -(w*100 + f), true
	}
	return w*100 + f, true
}

// Pools reads the balance and returns the pools, checking that both are present and that they
// sum to the real balance (features/wallet/pools.feature: "the two pools always sum to the real
// balance", asserted on every read).
func (s *Session) Pools() (deposit, converted int64) {
	s.t.Helper()
	r := s.Balance()
	s.Want(r, 200)
	d, okD := r.Minor("depositPoolBalance")
	c, okC := r.Minor("convertedPoolBalance")
	balance, _ := r.Minor("realBalance")
	if !okD || !okC {
		s.t.Errorf("the balance carries no pools: %s", r.Raw)
	} else if d+c != balance {
		s.t.Errorf("the pools %d + %d do not sum to the real balance %d", d, c, balance)
	}
	return d, c
}

// PoolsAre reads the balance and checks the pools.
func (s *Session) PoolsAre(deposit, converted int64) {
	s.t.Helper()
	if d, c := s.Pools(); d != deposit || c != converted {
		s.t.Errorf("pools: deposit %d, converted %d; the feature says %d and %d", d, c, deposit, converted)
	}
}

// PoolsRose reads the balance and checks that each pool rose by its amount since `before`.
func (s *Session) PoolsRose(beforeDeposit, beforeConverted, deposit, converted int64) {
	s.t.Helper()
	s.PoolsAre(beforeDeposit+deposit, beforeConverted+converted)
}

// DepositPart checks a bet's `fundedDepositAmount` or a win's `wonDepositAmount`.
func (s *Session) DepositPart(r Response, field string, want int64) {
	s.t.Helper()
	if got, ok := r.Minor(field); !ok || got != want {
		s.t.Errorf("%s: %q; the feature says %d", field, r.Str(field), want)
	}
}

// ConvertedPool puts `amount` in the converted pool by the original's own rule: an award of
// `amount` + 100 with no wagering requirement converts on the first bet it funds (100 from the
// record, real balance 0), the rest joining the converted pool; its pending cap is then closed and
// the round settled, so nothing of it is left to touch a later step. Call it before any deposit.
func (s *Session) ConvertedPool(amount int64) {
	s.t.Helper()
	if amount <= 0 {
		return
	}
	s.Want(s.AwardBonus("converted", Award{Amount: amount + 100, WageringMultiplier: 0, MaxWin: amount + 100}), 200)
	s.Want(s.Bet("converted-round", 100, BetOptions{}), 200)
	s.Want(s.SettleLoss("converted-round"), 200)
	s.Want(s.ClosePendingCap(), 200)
}

// StartingPools makes the feature's starting pools: the converted pool by ConvertedPool, then the
// deposit pool by a deposit.
func (s *Session) StartingPools(deposit, converted int64) {
	s.t.Helper()
	s.ConvertedPool(converted)
	s.Funded(deposit)
	s.PoolsAre(deposit, converted)
}

// Shortfall is a correction debit allowed to take real balance negative: the original's
// allow-negative bet.
func (s *Session) Shortfall(round string, amount int64) Response {
	return s.Bet(round, amount, BetOptions{AllowNegative: true})
}

func ptr[T any](v T) *T { return &v }
