package attributiondriver

import (
	"net/http"
	"testing"
	"time"
)

// A scenario the original answers otherwise than its feature expects fails in the run's result and is
// still recorded: the recording is the original's behaviour, and the failure is a divergence plan.md
// names. Made-up values (an address, a browser) are literals, the same on every run.

const (
	visitorIP = "203.0.113.7" // a documentation address (RFC 5737); never a real visitor's
	visitorUA = "Mozilla/5.0 (attribution-driver)"
)

// Def is one recording: a feature scenario, or one example of an outline.
type Def struct {
	Name        string // as the feature names the scenario
	Example     string // an outline's example, by its first column, or an edge the driver adds; empty for a scenario
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Scenario is the recording's name: the feature's, with an example in brackets.
func (d Def) Scenario() string {
	if d.Example == "" {
		return d.Name
	}
	return d.Name + " [" + d.Example + "]"
}

const (
	landing = "a landing with a valid tag records one click per browser session and emits a click fact"
	money   = "what happens to an attributed player's money is emitted as the matching fact with its amount and reference"
)

// EdgeNoTag is the click with no `btag`: no feature scenario names it, so it is the landing's, marked.
const EdgeNoTag = landing + " [without a tag]"

// Link seeds, through substrate as scenario 17 does, a CPA plan, an affiliate on it and a link for them
// under `suffix` ("" for the first, "2" for a second): `{plan<suffix>}`, `{affiliate<suffix>}` and
// `{btag<suffix>}`, the last the bare UUID the original's links are and the port's legacy tag.
func Link(s *Session, suffix string) {
	brand, now := s.Value("brand"), time.Now().UTC().Format(time.RFC3339)
	planName := "Gold CPA-" + s.Value("brand")[len("brand-"):] + suffix
	plan := NameUUID("aff.plan:" + brand + ":" + planName)
	s.Remember("plan"+suffix, plan)
	affiliate, btag := s.UUID("affiliate"+suffix), s.UUID("btag"+suffix)
	s.Setup("the commission plan"+suffix, "affiliate-plans-"+brand, plan, map[string]string{
		"planName": planName, "brandId": brand, "planType": "CPA", "cpaAmount": "50.00", "status": "ACTIVE",
	})
	s.Setup("the affiliate"+suffix, "affiliates-"+brand, affiliate, map[string]string{
		"affiliateId": affiliate, "email": "aff" + suffix + "@example.com", "brandId": brand, "planId": plan,
		"status": "ACTIVE", "createdAt": now,
	})
	link := map[string]string{"btag": btag, "affiliateId": affiliate, "brandId": brand, "createdAt": now}
	s.Setup("the link in the tag index"+suffix, "affiliate-links", btag, link)
	s.Setup("the link under the brand"+suffix, "affiliate-links-"+brand, btag, link)
}

// Click posts a landing as the player frontend sends it, with the tag `btag` names ("" for none).
func Click(s *Session, name, btag string) Response {
	body := map[string]any{
		"ip": visitorIP, "userAgent": visitorUA, "deviceType": "mobile",
		"utmSource": "network", "utmMedium": "affiliate", "utmCampaign": "autumn", "utmContent": "banner-1",
		"clickId": s.UUID("clickId"),
	}
	if btag != "" {
		body["btag"] = s.Value(btag)
	}
	return s.Affiliate(name, http.MethodPost, "/v1/track/click", body, Public)
}

// Register notifies the registration of `{player}` with the tag `btag` names.
func Register(s *Session, name, btag, clickID string) Response {
	body := map[string]any{"playerId": s.UUID("player"), "btag": s.Value(btag),
		"registeredAt": time.Now().UTC().Format(time.RFC3339), "country": "GB"}
	if clickID != "" {
		body["clickId"] = clickID
	}
	return s.Notify(name, "registration", body)
}

// Registered reads the registration twin the original writes for `{player}`.
func Registered(s *Session) Response {
	return s.Observe("the player's registration", "affiliate-registration-"+s.Value("brand"), s.Value("player"),
		func(r Response) bool { return r.Status == 200 && r.Str("affiliateId") != "" })
}

// Bound is the seed and the registration every money scenario starts from.
func Bound(s *Session) {
	Link(s, "")
	s.WantField(s.Want(Register(s, "notify the registration", "btag", ""), 200), "affiliateId", s.Value("affiliate"))
}

// Deposit notifies a captured deposit of 50.00 GBP under the request id `txn`.
func Deposit(s *Session, name, txn string) Response {
	return s.Notify(name, "deposit", map[string]any{"playerId": s.Value("player"), "amount": "50.00", "currency": "GBP",
		"transactionId": s.UUID(txn)})
}

// Revenue reads the affiliate's revenue twin for this month until it counts `ftd` first deposits.
func Revenue(s *Session, ftd string) Response {
	affiliate, brand := s.Value("affiliate"), s.Value("brand")
	id := NameUUID("aff.rev:" + affiliate + ":" + brand + ":" + time.Now().UTC().Format("2006-01"))
	s.Remember("revenue", id)
	return s.Observe("the affiliate's revenue this month", "affiliate-revenue-"+affiliate+"-"+brand, id,
		func(r Response) bool { return r.Status == 200 && r.Str("ftdCount") == ftd })
}

// NotDriven are the scenarios of features/attribution/ no recording of the original can show, and why
// (contracts/recording.md). Each is held by the port's own suites.
var NotDriven = map[string]string{
	"a tag older than the attribution window binds nothing and the registration is organic":                                  "the original binds a tag of any age",
	"a tag the brand cannot verify never fails a registration and is kept with its reason [a bad signature]":                 "the original's tags are bare UUIDs and carry no signature",
	"a tag the brand cannot verify never fails a registration and is kept with its reason [an unknown network]":              "the original knows no networks",
	"a tag the brand cannot verify never fails a registration and is kept with its reason [a key the network has withdrawn]": "the original knows no signing keys",
	"a tag for a revoked link binds nothing and the record says the link was revoked":                                        "the original's registration does not read a link's status",
	"a later captured deposit emits only a deposit fact":                                                                     "the original counts a first deposit per month, by the clock; a deposit in a later month cannot be driven, and MoneySuite holds the rule",
	"one daily GGR fact is emitted per player when the brand's reporting day closes":                                         "the suite's scenario-15 recordings show it, with the affiliate service installed (tests/port/attribution/README.md)",
	"a round of a closed day that settles after the close emits a GGR adjustment for that day":                               "the original has no reporting day to close",
	"a network that opted into the round stream receives a round fact at settlement and the daily fact at the close":         "the original has no round stream",
	"a network with the default configuration receives the daily fact and no rounds":                                         "the original has no networks to configure",
	money + " [a withdrawal is completed]":                                                                                   "the original is told once, at the approval: the requested row's recording holds it",
	money + " [a withdrawal fails]":                                                                                          "nothing in the original tells the affiliate service of a failed withdrawal",
	money + " [a withdrawal is reversed]":                                                                                    "the requested row's recording holds the reversal after the request",
	"no fact carries a personal field":                                                                                       "the original keeps player ids in its twins; LeakSuite holds the rule",
}

// NotDrivenFeatures are the features the original has nothing of, and why.
var NotDrivenFeatures = map[string]string{
	"bonus-codes.feature": "the original's registration notification carries no bonus code",
	"carriers.feature":    "the original has no topic, feed or webhook for networks",
	"closure.feature":     "the original has no closure notification",
	"networks.feature":    "the original knows no networks, key documents or legacy-tag transition",
}

// Scenarios is every recording, in the features' order.
func Scenarios() []Def {
	return []Def{
		{Name: landing, Requirement: "012/FR-006",
			Run: func(t testing.TB, s *Session) {
				Link(s, "")
				r := s.WantField(s.Want(Click(s, "land with the tag", "btag"), 200), "ok", "true")
				s.Vary("$.clickId", "the original mints its own click id; the port answers the one sent")
				if r.Str("clickId") != "" {
					s.Grab("originalClickId", "$.clickId", r.Str("clickId"))
				}
			}},
		{Name: landing, Example: "without a tag", Requirement: "012/FR-001",
			Run: func(t testing.TB, s *Session) {
				s.WantField(s.Want(Click(s, "land with no tag", ""), 400), "error", "btag is required")
			}},
		{Name: "a visitor who registers within the attribution window is bound once and for good", Requirement: "012/FR-007",
			Run: func(t testing.TB, s *Session) {
				Link(s, "")
				r := s.Want(Click(s, "land with the tag", "btag"), 200)
				s.Vary("$.clickId", "the original mints its own click id")
				s.Grab("originalClickId", "$.clickId", r.Str("clickId"))
				s.WantField(s.Want(Register(s, "notify the registration", "btag", s.Value("originalClickId")), 200), "affiliateId", s.Value("affiliate"))
				Registered(s)
			}},
		{Name: "a later tag does not change a binding and is recorded as a later touch", Requirement: "012/FR-009",
			Run: func(t testing.TB, s *Session) {
				Link(s, "")
				Link(s, "2")
				s.WantField(s.Want(Register(s, "notify the registration with the first tag", "btag", ""), 200), "affiliateId", s.Value("affiliate"))
				s.WantField(s.Want(Register(s, "notify the registration with a second tag", "btag2", ""), 200), "duplicate", "true")
				s.WantField(Registered(s), "affiliateId", s.Value("affiliate"))
			}},
		{Name: "a player's first captured deposit emits a first-deposit fact and a deposit fact, the first once in the player's life", Requirement: "012/FR-015",
			Run: func(t testing.TB, s *Session) {
				Bound(s)
				s.WantField(s.Want(Deposit(s, "notify the first deposit", "txn"), 200), "affiliateId", s.Value("affiliate"))
				Revenue(s, "1")
				s.Want(Deposit(s, "notify a second deposit", "txn2"), 200)
				Revenue(s, "1")
			}},
		{Name: money, Example: "a withdrawal is requested", Requirement: "012/FR-011",
			Run: func(t testing.TB, s *Session) {
				Bound(s)
				s.Want(s.Notify("notify the withdrawal", "withdrawal", map[string]any{"playerId": s.Value("player"),
					"amount": "20.00", "currency": "GBP", "requestId": s.UUID("withdrawal")}), 200)
				s.Want(s.Notify("notify the withdrawal's reversal", "withdrawal-reversal", map[string]any{
					"playerId": s.Value("player"), "requestId": s.Value("withdrawal")}), 200)
			}},
		{Name: money, Example: "restricted funds are awarded", Requirement: "012/FR-011",
			Run: func(t testing.TB, s *Session) {
				Bound(s)
				// As AffiliateServiceClient.notifyBonusCost sends it: a claim id where the route reads a
				// transaction id, so the original answers 400 and records nothing (the divergence).
				s.Want(s.Notify("notify the bonus cost as casino-service sends it", "bonus-cost", map[string]any{
					"playerId": s.Value("player"), "amount": "10.00", "currency": "GBP", "claimId": s.UUID("claim")}), 400)
			}},
		{Name: money, Example: "a deposit is charged back", Requirement: "012/FR-011",
			Run: func(t testing.TB, s *Session) {
				Bound(s)
				s.Want(Deposit(s, "notify the deposit", "txn"), 200)
				s.Want(s.Notify("notify the chargeback", "chargeback", map[string]any{"playerId": s.Value("player"),
					"transactionId": s.Value("txn"), "amount": "50.00", "currency": "GBP"}), 200)
			}},
	}
}
