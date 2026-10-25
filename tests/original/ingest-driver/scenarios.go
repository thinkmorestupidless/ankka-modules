package ingestdriver

import (
	"net/http"
	"testing"

	md "github.com/eitheror-io/ankka-casino/tests/original/merchant-driver"
)

// A scenario the original answers otherwise than its feature expects fails in the run's result and is
// still recorded: the recording is the original's behaviour, and the failure is the divergence plan.md
// names. Made-up values (a key, a ticket) are literals, the same on every run, so a replay sends them
// as they are.

const (
	madeUpKey    = "eo_live_000000000000000000000000"
	madeUpTicket = "00000000-0000-0000-0000-000000000000"
	repeated     = "t-1" // the request id cashier.feature repeats
)

// NotDriven are the scenarios of cashier.feature no recording of the original can show, and why
// (contracts/recording.md, *What is not recorded*). Each is held by the port's own suites.
var NotDriven = map[string]string{
	"a routing refusal is recorded against the sub-brand":                                                                                      "the original keeps no request record",
	"a cashier key the sub-brand disabled is refused within a minute":                                                                          "the original reads the keys on every request; the bound is the port's (SC-004)",
	"a refusal of a cashier key or for rate is an audit record naming the sub-brand where known and never the key":                             "the original writes no audit record",
	"a body larger than the installation's bound or not readable is refused before the key is checked and recorded under no sub-brand":         "the bound and the record are the port's (FR-021)",
	"a create the cashier did not answer in time is told to try again, and a repeat under its request id is forwarded again":                   "the edge and the cashier were one process: nothing to not answer",
	"a submit the payment provider refused for a reason the player can act on is answered rejected with the provider's words":                  "the simulator never refuses; held by the feature",
	"a sub-brand above the rate limit has the excess refused as too many with the time to wait, and the cashier receives only what was served": "the original has no rate limit",
	"one sub-brand refused as too many leaves another sub-brand served in the same minute":                                                     "the original has no rate limit",
	"a sub-brand whose rate limit an operator raised is served in full":                                                                        "the original has no rate limit",
}

func field(name string, value any) func(t testing.TB, s *Session) {
	return func(t testing.TB, s *Session) {
		s.SubBrand()
		r := s.Want(Create(s, "create cashier session", "session", Body(s, s.ID("txn"), map[string]any{name: value}), md.MerchantKey, nil), 400)
		s.WantField(r, "error", "INVALID_REQUEST")
	}
}

// Scenarios is every recording, in cashier.feature's order.
func Scenarios() []Def {
	return []Def{
		{Name: "a cashier session created under an active key answers a cashier ticket and the terminals the routing tree allows", Requirement: "006/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Want(s.Open(5000, "EUR", s.Customer(true)), 200)
			}},
		{Name: "submitting a session naming an offered terminal initiates the payment and answers the payment id and the status", Requirement: "006/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Deposit(true)
			}},
		{Name: "reading a payment tells its status in the frontend's words with its amounts, currency, provider, scheme, terminal and redirect address", Requirement: "006/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Deposit(true)
				s.Want(ReadAs(s, "read payment with the key", "merchantKey"), 200)
			}},
		{Name: "a cashier session that cannot be routed is refused naming why", Example: "a sub-brand with no routing tree deployed at any level", Requirement: "006/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				NoTree(s)
				s.WantField(s.Want(s.Open(5000, "EUR", s.Customer(true)), 400), "error", "NO_ROUTING_TREE")
			}},
		{Name: "a cashier session that cannot be routed is refused naming why", Example: "a sub-brand whose routing tree has no branch that matches", Requirement: "006/FR-006",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				NoMatch(s)
				s.WantField(s.Want(s.Open(5000, "EUR", s.Customer(true)), 400), "error", "NO_ROUTING_MATCH")
			}},
		{Name: "a request with no cashier key is refused as unauthenticated and no session exists", Requirement: "006/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.WantField(s.Want(Create(s, "create cashier session with no key", "session", Body(s, s.ID("txn"), nil), md.NoKey, nil), 401), "error", "UNAUTHENTICATED")
			}},
		{Name: "a request under a key no sub-brand holds active is refused without saying why", Example: "a cashier key no sub-brand holds", Requirement: "006/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				r := Create(s, "create cashier session under a made-up key", "session", Body(s, s.ID("txn"), nil), md.NoAuth, map[string]string{"merchantKey": madeUpKey})
				s.WantField(s.Want(r, 401), "error", "INVALID_MERCHANT_KEY")
			}},
		{Name: "a request under a key no sub-brand holds active is refused without saying why", Example: "a cashier key a sub-brand disabled", Requirement: "006/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Want(Create(s, "create cashier session while the key is active", "session", Body(s, s.ID("txn"), nil), md.MerchantKey, nil), 200)
				DisableKey(t, s)
				r := Create(s, "create cashier session under the disabled key", "", Body(s, s.ID("txn2"), nil), md.MerchantKey, nil)
				s.WantField(s.Want(r, 401), "error", "INVALID_MERCHANT_KEY")
			}},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "an amount that is not a whole number of minor units", Requirement: "006/FR-007",
			Run: field("amountCents", 50.5)},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "no amount", Requirement: "006/FR-007",
			Run: field("amountCents", nil)},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "no currency", Requirement: "006/FR-007",
			Run: field("currency", nil)},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "no player id", Requirement: "006/FR-007",
			Run: field("customerId", nil)},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "a player id without the brand part", Requirement: "006/FR-007",
			Run: func(t testing.TB, s *Session) { field("customerId", s.Customer(false))(t, s) }},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "no country", Requirement: "006/FR-007",
			Run: field("country", nil)},
		{Name: "a request missing or malforming a field is refused naming the field before the cashier is asked", Example: "no return address", Requirement: "006/FR-007",
			Run: field("redirectUrl", nil)},
		{Name: "a repeated create under the same request id with the same content returns the first session and asks the cashier nothing", Requirement: "006/FR-010",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				body := Body(s, repeated, nil)
				first := s.Want(Create(s, "create cashier session", "session", body, md.MerchantKey, nil), 200)
				again := s.Want(Create(s, "create it again", "session2", body, md.MerchantKey, nil), 200)
				s.WantField(again, "session_token", first.Str("session_token"))
			}},
		{Name: "a repeated create under the same request id with a different amount is refused naming the request id", Requirement: "006/FR-010",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Want(Create(s, "create cashier session", "session", Body(s, repeated, nil), md.MerchantKey, nil), 200)
				r := Create(s, "create again with another amount", "session2", Body(s, repeated, map[string]any{"amountCents": 6000}), md.MerchantKey, nil)
				s.WantField(s.Want(r, 409), "error", "REQUEST_ID_REUSED")
			}},
		{Name: "a request id belongs to the sub-brand and not to the installation", Requirement: "006/FR-010",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				Other(s)
				s.Want(Create(s, "create cashier session", "session", Body(s, repeated, nil), md.MerchantKey, nil), 200)
				s.Want(Create(s, "another sub-brand creates under the same request id", "otherSession", Body(s, repeated, nil), md.NoAuth,
					map[string]string{"merchantKey": s.Value("otherMerchantKey")}), 200)
			}},
		{Name: "a create with no request id is served under one the shared payments mints, and the same content again makes a second session", Requirement: "006/FR-010",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				body := Body(s, "", nil)
				first := s.Want(Create(s, "create cashier session with no request id", "session", body, md.MerchantKey, nil), 200)
				again := s.Want(Create(s, "the same again", "session2", body, md.MerchantKey, nil), 200)
				if first.Str("session_token") == again.Str("session_token") {
					t.Errorf("the second create answered the first session")
				}
			}},
		{Name: "submitting a session already submitted returns the first submit's answer and makes no second payment", Requirement: "006/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Deposit(true)
				s.WantField(s.Want(s.Submit("SIM"), 200), "paymentId", s.Value("payment"))
			}},
		{Name: "submitting a session naming a terminal it did not offer is refused naming the terminal", Requirement: "006/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Want(s.Open(5000, "EUR", s.Customer(true)), 200)
				s.Want(s.Submit("sim-gbp"), 400)
			}},
		{Name: "a submit under a cashier ticket no session has is refused as not found", Requirement: "006/FR-009",
			Run: func(t testing.TB, s *Session) {
				r := s.Merchant("submit", http.MethodPost, "/v1/cashier/sessions/"+madeUpTicket+"/submit", map[string]any{"selectedTerminalEntityId": "SIM"}, md.NoAuth, nil)
				s.WantField(s.Want(r, 404), "error", "SESSION_NOT_FOUND")
			}},
		{Name: "a payment read by the sub-brand that created it returns its state", Requirement: "006/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Deposit(true)
				s.Want(ReadAs(s, "read payment with the key", "merchantKey"), 200)
			}},
		{Name: "a payment read by another sub-brand is refused as not found", Requirement: "006/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Deposit(true)
				Other(s)
				s.WantField(s.Want(ReadAs(s, "read payment with another sub-brand's key", "otherMerchantKey"), 404), "error", "PAYMENT_NOT_FOUND")
			}},
		{Name: EdgeHeaderCase, Requirement: "006/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.SubBrand()
				s.Want(Create(s, "create cashier session, the header spelt MerchantKey", "session", Body(s, s.ID("txn"), nil), md.NoAuth,
					map[string]string{"MerchantKey": s.Value("merchantKey")}), 200)
			}},
	}
}

// EdgeHeaderCase is spec 006's edge case, not a feature scenario: the header is read whatever its case.
const EdgeHeaderCase = "a key presented in a different header case"
