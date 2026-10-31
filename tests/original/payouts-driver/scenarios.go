package payoutsdriver

import (
	"net/http"
	"testing"
)

// A scenario the original answers otherwise than its feature expects fails in the run's result and is
// still recorded: the recording is the original's behaviour, and the failure is a finding for the
// spec. A scenario in Marked is a divergence the plan declares (plan.md, *Divergences*): it records
// what the original does and asserts only what the original and the port share, never the port's
// behaviour.

// Def is one recording: a feature scenario, or one example of an outline.
type Def struct {
	Name        string // as features/payouts/*.feature names the scenario
	Example     string // an outline's example, by its first column; empty for a scenario
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Scenario is the recording's name: the feature's, with an outline's example in brackets.
func (d Def) Scenario() string {
	if d.Example == "" {
		return d.Name
	}
	return d.Name + " [" + d.Example + "]"
}

// Extra are recordings of behaviour the original has that no feature scenario names: kept so the
// port's parity tests hold the divergence they show.
var Extra = map[string]bool{
	ExtraGigadat: true,
}

const ExtraGigadat = "a Gigadat request missing its method fields is accepted and parked for manual payment at approval"

// Marked are the recordings of a declared divergence, and the divergence (plan.md, *Divergences*;
// contracts/surface.md).
var Marked = map[string]string{
	rejectedAtInitiation: "the original parks a payout the gateway refuses at initiation for manual payment (FR-018)",
	turnoverPending:      "the original's turnover refusal names the amount remaining; the port's names none (R4, Found during implementation)",
	visaPresent:          "the original lists under the player twin's currency, GBP when it has none; the port under the wallet's (R5)",
	playerList:           "the original lists the pending request only; the port every request, newest first (contracts/surface.md)",
	ExtraGigadat:         "the original accepts a Gigadat request without its method fields and parks it at approval; the port refuses it at the request (FR-004, R4)",
}

const (
	rejectedAtInitiation = "a request rejected at initiation returns the money and tells the player to try another terminal"
	turnoverPending      = "a request while deposit turnover is pending is refused as blocked by turnover"
	visaPresent          = "a VISA terminal is present for a player who deposited by VISA"
	playerList           = "a player lists their requests newest first with amount, terminal, time and status"
)

// NotDriven are the feature scenarios no recording of the original can show, and why (spec 007
// research R2). Each is held by the port's own suites; tests/port/payouts/README.md repeats them once
// the recordings are made (quickstart §3).
var NotDriven = map[string]string{
	"an amount that is zero, negative or a decimal is refused naming the amount [10.50]":                                                        "the original takes major units, where 10.50 is an ordinary amount; the minor-unit refusal is the port's (WireTranslation)",
	"a request whose clawback under a funds and winnings policy would leave real balance short is refused naming the most they could withdraw":  "a positive clawback needs real winnings booked while the record is active, and the driver books no game rounds",
	"a request with a record under a funds policy requires confirmation naming a clawback of zero":                                              "an ad hoc grant carries no withdrawal policy; a funds policy comes from an offer's rule snapshot (BonusGrantService:516), which the driver does not create",
	"approving or rejecting a request already approved is refused as not pending [approves]":                                                    "the original's defect (R1): a second approval runs the payout again; not driven",
	"approving or rejecting a request already approved is refused as not pending [rejects]":                                                     "the original's defect (R1): a rejection after approval refunds; not driven",
	"a withdrawal payment the gateway answers completed confirms the hold and completes the request":                                            "psp-stub's four payout providers answer a payout initiated, never completed (tests/port/psp-gateway)",
	"a withdrawal payment in doubt to a polled provider is asked for its status after thirty minutes":                                           "the original's sweeper polls on its own five-minute schedule; a run does not wait it out",
	"a status that is not final is asked again five minutes later":                                                                              "the original's sweeper polls on its own five-minute schedule; a run does not wait it out",
	"a final status settles the request accordingly [completed]":                                                                                "the original's sweeper polls on its own five-minute schedule; a run does not wait it out",
	"a final status settles the request accordingly [failed]":                                                                                   "the original's sweeper polls on its own five-minute schedule; a run does not wait it out",
	"a final status settles the request accordingly [reversed]":                                                                                 "the original's sweeper polls on its own five-minute schedule; a run does not wait it out",
	"a provider saying the withdrawal payment was never created marks the request needs review and stops the polling":                           "the original's sweeper polls on its own five-minute schedule; a run does not wait it out",
	"a withdrawal payment in doubt to a provider that only sends webhooks is marked needs review after the provider's window [cozeni]":          "the provider's window (24 hours) is not waited by a run",
	"a withdrawal payment in doubt to a provider that only sends webhooks is marked needs review after the provider's window [gigadat]":         "the provider's window (5 days) is not waited by a run",
	"a withdrawal payment in doubt to a provider that only sends webhooks is marked needs review after the provider's window [morphosis]":       "the provider's window (5 days) is not waited by a run",
	"a withdrawal payment the gateway cannot answer is in doubt and reconciled, never awaiting manual payment [cannot be reached]":              "needs psp-gateway down mid-run, which a run against the shared kind stack does not do",
	"a withdrawal payment the gateway cannot answer is in doubt and reconciled, never awaiting manual payment [answers something unrecognised]": "psp-gateway answers only the statuses it knows; nothing on the stack answers otherwise",
}

// Scenarios is every recording the driver makes, in the features' order.
func Scenarios() []Def {
	var defs []Def
	defs = append(defs, requests()...)
	defs = append(defs, review()...)
	defs = append(defs, settlement()...)
	defs = append(defs, terminals()...)
	defs = append(defs, reads()...)
	defs = append(defs, Def{Name: ExtraGigadat, Requirement: "007/FR-004",
		Run: func(t testing.TB, s *Session) {
			s.Ready("", 10000, true)
			gigadat := s.Terminal("gigadatTerminal", TerminalSpec{Name: "payouts driver gigadat", Gateway: "gigadat", Scheme: "interac",
				Automated: true})
			r := s.Request("request a withdrawal naming no method", "", Ask(5000, gigadat, nil), "requestId", false)
			s.Want(r, http.StatusCreated)
			// marked: the port refuses here naming payoutMethod
			s.Want(s.Decide("approve", "approve", "requestId", nil, false), http.StatusOK)
			s.Settled("requestId", "AWAITING_MANUAL_PAYOUT")
		}})
	return defs
}

// requests.feature
func requests() []Def {
	defs := []Def{
		{Name: "a request by a verified player with nothing pending holds the amount and is listed pending review", Requirement: "007/FR-007",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, true)
				s.Want(s.Destination("save the player's destination", "", FirstName, LastName, IBAN, "destinationId", true), http.StatusCreated)
				r := s.Request("request a withdrawal", "", Ask(5000, s.Manual(true), map[string]any{"destinationId": s.Value("destinationId")}), "requestId", false)
				s.WantField(s.Want(r, http.StatusCreated), "status", "PENDING_REVIEW")
				s.Balance("")
				if got := Listed(s.Want(s.Requests("the player lists their requests", ""), http.StatusOK), s.Value("requestId")); got != "PENDING_REVIEW" {
					s.t.Errorf("the player's list shows the request as %q", got)
				}
				q := s.Eventually(func() Response {
					return s.Casino("an operator reads the queue", http.MethodGet, s.Queue()+"?status=PENDING_REVIEW&playerSearch="+s.Value("playerId"), nil, Operator)
				}, func(r Response) bool { return Listed(r, s.Value("requestId")) != "" })
				if got := Listed(s.Want(q, http.StatusOK), s.Value("requestId")); got != "PENDING_REVIEW" {
					s.t.Errorf("the queue shows the request as %q", got)
				}
			}},
		{Name: "a second request while one is pending review is refused and nothing moves", Requirement: "007/FR-007",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Held("", 3000, s.Manual(false), "requestId", nil)
				r := s.Request("request a withdrawal again", "", Ask(3000, s.Manual(false), nil), "", false)
				s.WantField(s.Want(r, http.StatusConflict), "error", "WITHDRAWAL_REQUEST_ALREADY_PENDING")
				s.Balance("")
			}},
	}
	for _, a := range []struct {
		example string
		amount  int64
	}{{"0", 0}, {"-100", -100}} {
		a := a
		defs = append(defs, Def{Name: "an amount that is zero, negative or a decimal is refused naming the amount", Example: a.example, Requirement: "007/FR-004",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				r := s.Request("request a withdrawal of "+Decimal(a.amount), "", Ask(a.amount, s.Manual(false), nil), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "INVALID_AMOUNT")
				s.Balance("")
			}})
	}
	defs = append(defs,
		Def{Name: "a request to a terminal that requires a destination is refused when none is named", Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				r := s.Request("request a withdrawal naming no destination", "", Ask(5000, s.Manual(true), nil), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "DESTINATION_REQUIRED")
			}},
		Def{Name: "a request to a crypto terminal is refused when no address is supplied", Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				crypto := s.Terminal("cryptoTerminal", TerminalSpec{Name: "payouts driver crypto", Gateway: "cozeni", Scheme: "BTC"})
				r := s.Request("request a withdrawal naming no address", "", Ask(5000, crypto, nil), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "WALLET_ADDRESS_REQUIRED")
			}},
		Def{Name: "a request to a crypto terminal naming a coin it does not support is refused naming the coins it supports", Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				crypto := s.Terminal("cryptoTerminal", TerminalSpec{Name: "payouts driver crypto", Gateway: "cozeni", Scheme: "BTC",
					SupportedCurrencies: "BTC,ETH"})
				r := s.Request("request a withdrawal in DOGE", "", Ask(5000, crypto, map[string]any{"walletAddress": WalletAddress, "cryptoCurrency": "DOGE"}), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "CRYPTO_CURRENCY_NOT_SUPPORTED")
				s.WantField(r, "supportedCurrencies", `["BTC","ETH"]`)
			}},
		Def{Name: "a destination that is not the player's own is refused as invalid", Example: "a destination that is another player's", Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, true)
				s.Active("other", true)
				s.Want(s.Destination("save the other player's destination", "other", FirstName, LastName, OtherIBAN, "otherDestinationId", true), http.StatusCreated)
				r := s.Request("request a withdrawal naming the other player's destination", "",
					Ask(5000, s.Manual(true), map[string]any{"destinationId": s.Value("otherDestinationId")}), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "INVALID_PAYOUT_DESTINATION")
			}},
		Def{Name: "a destination that is not the player's own is refused as invalid", Example: "a destination the player deleted", Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, true)
				s.Want(s.Destination("save the player's destination", "", FirstName, LastName, IBAN, "destinationId", true), http.StatusCreated)
				s.Want(s.Setup("delete the destination", "casino", http.MethodDelete, s.PlayerPath("")+"/payout-destinations/"+s.Value("destinationId")+"?currency=EUR", nil, Player), http.StatusNoContent)
				r := s.Request("request a withdrawal naming the deleted destination", "",
					Ask(5000, s.Manual(true), map[string]any{"destinationId": s.Value("destinationId")}), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "INVALID_PAYOUT_DESTINATION")
			}},
		Def{Name: "a request above real balance is refused as insufficient funds and nothing moves", Requirement: "007/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 3000, false)
				r := s.Request("request a withdrawal of 50.00", "", Ask(5000, s.Manual(false), nil), "", false)
				s.WantField(s.Want(r, http.StatusPaymentRequired), "error", "INSUFFICIENT_FUNDS")
				s.Balance("")
			}},
	)
	for _, k := range []struct{ example, status, message string }{
		{"not submitted", "unverified", "Please verify your identity before withdrawing"},
		{"under review", "pending", "Your identity documents are under review"},
		{"rejected", "rejected", "Your identity documents were not accepted — please re-submit"},
	} {
		k := k
		defs = append(defs, Def{Name: "a player whose identity is not verified is refused telling them what to do", Example: k.example, Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Active("", false)
				s.KYC("", k.status)
				s.Fund("", 10000, "payouts-driver-psp", "card")
				r := s.Want(s.Request("request a withdrawal", "", Ask(5000, s.Manual(false), nil), "", false), http.StatusPaymentRequired)
				s.WantField(r, "error", "WITHDRAWAL_BLOCKED_KYC")
				s.WantField(r, "kycStatus", k.status)
				s.WantField(r, "message", k.message)
			}})
	}
	weekly, monthly := int64(100000), int64(300000)
	defs = append(defs,
		Def{Name: "a verified player passes the identity gate", Requirement: "007/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Want(s.Request("request a withdrawal", "", Ask(5000, s.Manual(false), nil), "requestId", false), http.StatusCreated)
			}},
		Def{Name: "a request that would pass the brand's weekly withdrawal limit is refused naming the limit, what was used and what remains", Requirement: "007/FR-005a",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 200000, false)
				s.Limits(&weekly, nil)
				s.Held("", 80000, s.Manual(false), "earlierId", nil)
				s.OnWallet("", "earlierId")
				r := s.Want(s.Request("request a withdrawal of 300.00", "", Ask(30000, s.Manual(false), nil), "", false), http.StatusPaymentRequired)
				s.WantField(r, "error", "WITHDRAWAL_BLOCKED_WEEKLY_LIMIT")
				s.WantField(r, "weeklyLimit", "1000")
				s.WantField(r, "weeklyUsed", "800")
				s.WantField(r, "weeklyRemaining", "200")
			}},
		Def{Name: "a request that would pass the brand's monthly withdrawal limit is refused naming the limit, what was used and what remains", Requirement: "007/FR-005a",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 400000, false)
				s.Limits(nil, &monthly)
				s.Held("", 290000, s.Manual(false), "earlierId", nil)
				s.OnWallet("", "earlierId")
				r := s.Want(s.Request("request a withdrawal of 200.00", "", Ask(20000, s.Manual(false), nil), "", false), http.StatusPaymentRequired)
				s.WantField(r, "error", "WITHDRAWAL_BLOCKED_MONTHLY_LIMIT")
				s.WantField(r, "monthlyLimit", "3000")
				s.WantField(r, "monthlyUsed", "2900")
				s.WantField(r, "monthlyRemaining", "100")
			}},
	)
	for _, o := range []struct {
		example string
		settle  func(s *Session)
		status  string
	}{
		{"cancelled", func(s *Session) { s.Want(s.Cancel("cancel the earlier request", "", "earlierId", true), http.StatusOK) }, "CANCELLED"},
		{"rejected", func(s *Session) {
			s.Want(s.Decide("reject the earlier request", "reject", "earlierId", map[string]any{"reason": "payouts driver"}, true), http.StatusOK)
		}, "REJECTED"},
		{"failed", func(s *Session) {
			s.Want(s.Decide("approve the earlier request", "approve", "earlierId", nil, true), http.StatusOK)
			s.Settled("earlierId", "AWAITING_MANUAL_PAYOUT")
			s.Want(s.Decide("mark the earlier payment failed", "mark-payout-failed", "earlierId", map[string]any{"reason": "payouts driver"}, true), http.StatusOK)
		}, "FAILED"},
	} {
		o := o
		defs = append(defs, Def{Name: "a withdrawal request cancelled, rejected or failed does not count towards a withdrawal limit", Example: o.example, Requirement: "007/FR-005b",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 200000, false)
				s.Limits(&weekly, nil)
				s.Held("", 80000, s.Manual(false), "earlierId", nil)
				o.settle(s)
				s.Settled("earlierId", o.status)
				r := s.Request("request a withdrawal of 300.00", "", Ask(30000, s.Manual(false), nil), "requestId", false)
				s.WantField(s.Want(r, http.StatusCreated), "status", "PENDING_REVIEW")
			}})
	}
	defs = append(defs,
		Def{Name: turnoverPending, Requirement: "007/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 50000, false)
				s.Turnover("", 25000)
				r := s.Want(s.Request("request a withdrawal", "", Ask(5000, s.Manual(false), nil), "", false), http.StatusPaymentRequired)
				s.WantField(r, "error", "WITHDRAWAL_BLOCKED_TURNOVER") // marked: the original also names totalRemaining
			}},
		Def{Name: "a request with a record that forfeits on withdrawal is refused until the player confirms the forfeit", Requirement: "007/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Grant("")
				r := s.Want(s.Request("request a withdrawal without confirming the forfeit", "", Ask(5000, s.Manual(false), nil), "", false), http.StatusPaymentRequired)
				s.WantField(r, "error", "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION")
			}},
		Def{Name: "a request confirming the forfeit places the hold, forfeits the record and applies the clawback once", Requirement: "007/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Grant("")
				r := s.Request("request a withdrawal confirming the forfeit", "", Ask(5000, s.Manual(false), map[string]any{"confirmForfeit": true}), "requestId", false)
				s.WantField(s.Want(r, http.StatusCreated), "bonusForfeited", "true")
				s.Balance("")
			}},
		Def{Name: "a request above real balance alone with restricted funds in play is refused as insufficient funds, not as insufficient after forfeit", Requirement: "007/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 3000, false)
				s.Grant("")
				r := s.Request("request a withdrawal of 50.00", "", Ask(5000, s.Manual(false), nil), "", false)
				s.WantField(s.Want(r, http.StatusPaymentRequired), "error", "INSUFFICIENT_FUNDS")
			}},
	)
	return defs
}

// review.feature
func review() []Def {
	return []Def{
		{Name: "an operator with a finance or admin role approves a pending request and the withdrawal payment is pursued", Requirement: "007/FR-015",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Held("", 5000, s.Manual(false), "requestId", nil)
				// a terminal with no automated payout: the original answers it awaiting manual payment
				s.Want(s.Decide("approve", "approve", "requestId", nil, false), http.StatusOK)
				s.Settled("requestId", "AWAITING_MANUAL_PAYOUT")
			}},
		{Name: "an operator rejects a pending request with a reason, the held amount returns and the reason is recorded", Requirement: "007/FR-015",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Held("", 5000, s.Manual(false), "requestId", nil)
				r := s.Decide("reject with a reason", "reject", "requestId", map[string]any{"reason": "mismatched name"}, false)
				s.WantField(s.Want(r, http.StatusOK), "status", "REJECTED")
				s.Settled("requestId", "REJECTED")
				s.Balance("")
			}},
		{Name: "an operator whose roles name another brand is refused this brand's queue", Requirement: "007/FR-001",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Casino("an operator of another brand opens the queue", http.MethodGet, s.Queue(), nil, Outsider), http.StatusForbidden)
			}},
		{Name: "a player cancels a request pending review and the amount returns", Requirement: "007/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Held("", 5000, s.Manual(false), "requestId", nil)
				s.WantField(s.Want(s.Cancel("cancel the request", "", "requestId", false), http.StatusOK), "status", "CANCELLED")
				s.Settled("requestId", "CANCELLED")
				s.Balance("")
			}},
		{Name: "a player cancelling an approved request is refused as not cancellable", Requirement: "007/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Approved("", 5000, "requestId")
				s.WantField(s.Want(s.Cancel("cancel the approved request", "", "requestId", false), http.StatusConflict), "error", "WITHDRAWAL_REQUEST_NOT_CANCELLABLE")
			}},
	}
}

// settlement.feature
func settlement() []Def {
	defs := []Def{
		{Name: "an initiated withdrawal payment whose outcome arrives as completed completes the request", Requirement: "007/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Initiated("", 5000, "requestId", false, false)
				s.WantField(s.Want(s.ProviderSays("the provider's outcome arrives as completed", "", "requestId", "COMPLETED", ""), http.StatusOK), "ok", "true")
				s.Settled("requestId", "COMPLETED")
			}},
		{Name: "an initiated withdrawal payment whose outcome arrives as failed returns the held amount and fails the request", Requirement: "007/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Initiated("", 5000, "requestId", false, false)
				s.WantField(s.Want(s.ProviderSays("the provider's outcome arrives as failed", "", "requestId", "FAILED", "limitExceeded"), http.StatusOK), "ok", "true")
				s.Settled("requestId", "FAILED")
				s.Balance("")
			}},
		{Name: rejectedAtInitiation, Requirement: "007/FR-018",
			Run: func(t testing.TB, s *Session) {
				// marked: the gateway refuses the empty credential REJECTED; the original parks the request
				// for manual payment, where the port releases the hold
				s.Initiated("", 5000, "requestId", true, true)
				s.Want(s.Casino("an operator reads the request", http.MethodGet, s.Queue()+"/"+s.Value("requestId"), nil, Operator), http.StatusOK)
				s.Want(s.Requests("the player lists their requests", ""), http.StatusOK)
			}},
		{Name: "a request to a terminal without automated withdrawal payment awaits manual payment with the money held", Requirement: "007/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Held("", 5000, s.Manual(false), "requestId", nil)
				s.WantField(s.Want(s.Decide("approve", "approve", "requestId", nil, false), http.StatusOK), "status", "AWAITING_MANUAL_PAYOUT")
				s.Settled("requestId", "AWAITING_MANUAL_PAYOUT")
				s.Want(s.Requests("the player lists their requests", ""), http.StatusOK)
				s.Balance("")
			}},
	}
	states := []struct {
		example string
		arrange func(s *Session)
	}{
		{"awaiting manual payment", func(s *Session) { s.Ready("", 10000, false); s.Approved("", 5000, "requestId") }},
		{"approved and marked needs review", func(s *Session) {
			s.Initiated("", 5000, "requestId", false, false)
			s.NeedsReview("requestId")
		}},
	}
	for _, st := range states {
		st := st
		defs = append(defs, Def{Name: "an operator confirms a manual payment and the hold is confirmed", Example: st.example, Requirement: "007/FR-016",
			Run: func(t testing.TB, s *Session) {
				st.arrange(s)
				s.WantField(s.Want(s.Decide("confirm the manual payment", "confirm-payout", "requestId", nil, false), http.StatusOK), "status", "COMPLETED")
				s.Settled("requestId", "COMPLETED")
			}})
	}
	for _, st := range states {
		st := st
		defs = append(defs, Def{Name: "an operator marks a manual payment failed and the amount returns", Example: st.example, Requirement: "007/FR-016",
			Run: func(t testing.TB, s *Session) {
				st.arrange(s)
				r := s.Decide("mark the manual payment failed", "mark-payout-failed", "requestId", map[string]any{"reason": "returned by the bank"}, false)
				s.WantField(s.Want(r, http.StatusOK), "status", "FAILED")
				s.Settled("requestId", "FAILED")
				s.Balance("")
			}})
	}
	for _, a := range []struct{ example, action, name string }{
		{"confirms the manual payment", "confirm-payout", "confirm the manual payment"},
		{"marks the manual payment failed with a reason", "mark-payout-failed", "mark the manual payment failed"},
	} {
		a := a
		defs = append(defs, Def{Name: "confirming or failing a manual payment for a request in any other state is refused as not awaiting payment", Example: a.example, Requirement: "007/FR-016",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Held("", 5000, s.Manual(false), "requestId", nil)
				r := s.Decide(a.name, a.action, "requestId", map[string]any{"reason": "returned by the bank"}, false)
				s.WantField(s.Want(r, http.StatusConflict), "error", "WITHDRAWAL_NOT_AWAITING_PAYOUT")
			}})
	}
	defs = append(defs,
		Def{Name: "a completed request the provider reports charged back is reversed and the amount returns", Requirement: "007/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Completed("", 5000, "requestId")
				s.Want(s.ProviderSays("the provider reports it charged back", "", "requestId", "REVERSED", ""), http.StatusOK)
				s.Settled("requestId", "REVERSED")
				s.Balance("")
			}},
		Def{Name: "the same reversal arriving again moves nothing and returns the first outcome", Requirement: "007/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Completed("", 5000, "requestId")
				s.Want(s.ProviderSays("the provider reports it charged back", "", "requestId", "REVERSED", ""), http.StatusOK)
				s.Settled("requestId", "REVERSED")
				s.Want(s.ProviderSays("the same reversal arrives again", "", "requestId", "REVERSED", ""), http.StatusOK)
				s.Balance("")
			}},
		Def{Name: "a reversal for a request not completed is refused as not completed", Requirement: "007/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Approved("", 5000, "requestId")
				s.Want(s.ProviderSays("a reversal arrives for it", "", "requestId", "REVERSED", ""), http.StatusConflict)
				s.Balance("")
			}},
		Def{Name: "a provider's outcome reporting a request awaiting manual payment completed wins over the manual path", Requirement: "007/FR-019",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				s.Approved("", 5000, "requestId")
				s.Want(s.ProviderSays("the provider's outcome arrives as completed", "", "requestId", "COMPLETED", ""), http.StatusOK)
				s.Settled("requestId", "COMPLETED")
			}},
	)
	return defs
}

// terminals.feature
func terminals() []Def {
	visa := TerminalSpec{Name: "payouts driver visa", Gateway: "payouts-driver-visa", Scheme: "VISA"}
	defs := []Def{
		{Name: "a VISA terminal is absent for a player who never deposited by VISA", Requirement: "007/FR-011",
			Run: func(t testing.TB, s *Session) {
				s.Active("", false)
				s.Fund("", 10000, "payouts-driver-psp", "card")
				id := s.Terminal("visaTerminal", visa)
				if at := Offered(s.Want(s.Terminals("list the player's terminals", ""), http.StatusOK), id); at >= 0 {
					s.t.Errorf("the VISA terminal is offered at %d", at)
				}
			}},
		{Name: visaPresent, Requirement: "007/FR-011",
			Run: func(t testing.TB, s *Session) {
				s.Active("", false)
				s.Fund("", 10000, visa.Gateway, "VISA")
				id := s.Terminal("visaTerminal", visa)
				// marked: the original reads the deposits under the player twin's currency
				listed := s.Eventually(func() Response { return s.Terminals("list the player's terminals", "") },
					func(r Response) bool { return Offered(r, id) >= 0 })
				if at := Offered(s.Want(listed, http.StatusOK), id); at < 0 {
					s.t.Errorf("the VISA terminal is not offered")
				}
			}},
		{Name: "a terminal of a payment provider the player deposited with is listed first, in the same order on every read", Requirement: "007/FR-011",
			Run: func(t testing.TB, s *Session) {
				s.Active("", false)
				s.Fund("", 10000, "payouts-driver-b", "card")
				// by name, "A" would come first; the deposit puts "B" before it
				a := s.Terminal("firstByName", TerminalSpec{Name: "A payouts driver", Gateway: "payouts-driver-a", Scheme: "eubanking"})
				b := s.Terminal("depositedWith", TerminalSpec{Name: "B payouts driver", Gateway: "payouts-driver-b", Scheme: "eubanking"})
				for _, name := range []string{"list the player's terminals", "list them again"} {
					r := s.Want(s.Terminals(name, ""), http.StatusOK)
					if ia, ib := Offered(r, a), Offered(r, b); ib < 0 || ia < 0 || ib > ia {
						s.t.Errorf("%s: the deposited-with terminal at %d, the other at %d", name, ib, ia)
					}
				}
			}},
	}
	for _, st := range []struct {
		example string
		spec    TerminalSpec
	}{
		{"is inactive", TerminalSpec{Name: "payouts driver inactive", Gateway: "manual", Scheme: "eubanking", Inactive: true}},
		{"does not pay withdrawals", TerminalSpec{Name: "payouts driver deposits only", Gateway: "manual", Scheme: "eubanking", NoWithdrawals: true}},
	} {
		st := st
		defs = append(defs, Def{Name: "a terminal that is inactive or does not pay withdrawals is absent and a request naming it is refused", Example: st.example, Requirement: "007/FR-012",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 10000, false)
				id := s.Terminal("terminalId", st.spec)
				if at := Offered(s.Want(s.Terminals("list the player's terminals", ""), http.StatusOK), id); at >= 0 {
					s.t.Errorf("the terminal is offered at %d", at)
				}
				r := s.Request("request a withdrawal naming it", "", Ask(5000, id, nil), "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "TERMINAL_NOT_ELIGIBLE")
			}})
	}
	for _, n := range []struct {
		example, first, last string
		status               int
		error                string
	}{
		{"A. Example", FirstName, LastName, http.StatusCreated, ""},
		{"B. Other", "B.", "Other", http.StatusUnprocessableEntity, "PAYOUT_DESTINATION_AML_BLOCKED"},
	} {
		n := n
		defs = append(defs, Def{Name: "a destination is saved only in a name that matches the player's", Example: n.example, Requirement: "007/FR-013",
			Run: func(t testing.TB, s *Session) {
				s.Active("", true)
				r := s.Want(s.Destination("save a destination in the name "+n.example, "", n.first, n.last, IBAN, "destinationId", false), n.status)
				if n.error != "" {
					s.WantField(r, "error", n.error)
				}
				s.Destinations("list the player's destinations", "")
			}})
	}
	defs = append(defs,
		Def{Name: "saving a destination with no name on the profile is refused as profile incomplete", Requirement: "007/FR-013",
			Run: func(t testing.TB, s *Session) {
				s.Active("", false)
				r := s.Destination("save a destination", "", FirstName, LastName, IBAN, "", false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "PROFILE_INCOMPLETE")
			}},
		Def{Name: "setting a second destination as default makes the first no longer default", Requirement: "007/FR-013",
			Run: func(t testing.TB, s *Session) {
				s.Active("", true)
				s.Want(s.Destination("save the first destination", "", FirstName, LastName, IBAN, "firstId", true), http.StatusCreated)
				s.Want(s.Destination("save the second destination", "", FirstName, LastName, OtherIBAN, "secondId", true), http.StatusCreated)
				s.Want(s.Casino("set the second as default", http.MethodPut, s.PlayerPath("")+"/payout-destinations/"+s.Value("secondId")+"/set-default",
					map[string]any{"currency": Currency}, Player), http.StatusOK)
				s.Want(s.Destinations("list the player's destinations", ""), http.StatusOK)
			}},
		Def{Name: "deleting the default destination leaves it unoffered and no destination default", Requirement: "007/FR-013",
			Run: func(t testing.TB, s *Session) {
				s.Active("", true)
				s.Want(s.Destination("save the first destination", "", FirstName, LastName, IBAN, "firstId", true), http.StatusCreated)
				s.Want(s.Destination("save the second destination", "", FirstName, LastName, OtherIBAN, "secondId", true), http.StatusCreated)
				s.Want(s.Casino("delete the first", http.MethodDelete, s.PlayerPath("")+"/payout-destinations/"+s.Value("firstId")+"?currency=EUR", nil, Player), http.StatusNoContent)
				s.Want(s.Destinations("list the player's destinations", ""), http.StatusOK)
			}},
	)
	return defs
}

// reads.feature
func reads() []Def {
	// queue is a queue of three players' requests: pending review, approved and initiated, and marked
	// needs review. A player holds one pending request at a time in the original's wallet, so each is
	// another player's; the run's tag in their addresses narrows the queue to them.
	queue := func(s *Session) {
		s.Ready("", 10000, false)
		s.Held("", 1000, s.Manual(false), "pendingId", nil)
		s.Initiated("approved", 2000, "approvedId", false, false)
		s.Initiated("review", 3000, "reviewId", false, false)
		s.NeedsReview("reviewId")
	}
	return []Def{
		{Name: playerList, Requirement: "007/FR-014",
			Run: func(t testing.TB, s *Session) {
				s.Ready("", 20000, false)
				s.Held("", 1000, s.Manual(false), "firstId", nil)
				s.Want(s.Cancel("cancel the first", "", "firstId", true), http.StatusOK)
				s.Settled("firstId", "CANCELLED")
				s.Held("", 2000, s.Manual(false), "secondId", nil)
				s.Want(s.Cancel("cancel the second", "", "secondId", true), http.StatusOK)
				s.Settled("secondId", "CANCELLED")
				s.Held("", 3000, s.Manual(false), "thirdId", nil)
				// marked: the original lists the pending request only
				s.Want(s.Requests("the player lists their requests", ""), http.StatusOK)
			}},
		{Name: "a queue filtered by status pending review shows only pending requests", Requirement: "007/FR-014",
			Run: func(t testing.TB, s *Session) {
				queue(s)
				r := s.Want(s.Eventually(func() Response {
					return s.Casino("filter the queue by status pending review", http.MethodGet,
						s.Queue()+"?status=PENDING_REVIEW&playerSearch="+s.Value("tag"), nil, Operator)
				}, func(r Response) bool { return Listed(r, s.Value("pendingId")) != "" }), http.StatusOK)
				if Listed(r, s.Value("pendingId")) != "PENDING_REVIEW" || Listed(r, s.Value("approvedId")) != "" || Listed(r, s.Value("reviewId")) != "" {
					s.t.Errorf("the queue filtered by pending review: %s", string(r.Raw))
				}
			}},
		{Name: "a queue filtered by payment status needs review shows only those requests", Requirement: "007/FR-014",
			Run: func(t testing.TB, s *Session) {
				queue(s)
				r := s.Want(s.Eventually(func() Response {
					return s.Casino("filter the queue by payment status needs review", http.MethodGet,
						s.Queue()+"?status=ALL&payoutStatus=NEEDS_REVIEW&playerSearch="+s.Value("tag"), nil, Operator)
				}, func(r Response) bool { return Listed(r, s.Value("reviewId")) != "" }), http.StatusOK)
				if Listed(r, s.Value("reviewId")) == "" || Listed(r, s.Value("pendingId")) != "" || Listed(r, s.Value("approvedId")) != "" {
					s.t.Errorf("the queue filtered by needs review: %s", string(r.Raw))
				}
			}},
	}
}
