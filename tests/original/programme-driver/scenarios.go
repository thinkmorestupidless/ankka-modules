package programmedriver

import (
	"net/http"
	"strings"
	"testing"
	"time"
)

// A scenario the original answers otherwise than its feature expects is recorded as the original
// answers: where the answer is known to differ (a marked divergence of contracts/recording.md, or a
// finding of the survey), the driver wants the original's answer and says why beside it. Made-up
// values (a country, a campaign, an address of a creative) are literals, the same on every run.

// Def is one recording: a driver scenario of contracts/recording.md, named by the feature scenario
// it shows first. Covers names the other feature scenarios the same recording shows; the recording
// is one parity test, and its file is named by Name only.
type Def struct {
	Driver      string // the driver scenario as contracts/recording.md names it
	Name        string // the feature scenario, as the feature names it; an outline's example in brackets
	Covers      []string
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Scenario is the recording's name.
func (d Def) Scenario() string { return d.Name }

// Feature scenarios the recordings show, by the names their features give them.
const (
	approveOnDefaultPlan = "a manager for a brand approves an application on that brand's default plan and a manager for another brand cannot see it"
	rejectedMayReapply   = "a rejected applicant may apply again"
	duplicateApplication = "an application from an email with a pending or active membership at that brand is refused as a duplicate"

	planChanged = "a plan created or changed applies to the memberships that name it from the next fact onward and is audited"

	registrationRecorded  = "a registration fact records the player as attributed to the affiliate at that brand"
	registrationDuplicate = "a second registration fact for the same player at the same brand is ignored as a duplicate"
	firstDepositCPA       = "a first deposit at or above the plan's minimum deposit writes one CPA fact in the plan's currency"
	laterDeposit          = "a later deposit in any period writes no second CPA fact and is still counted in the player's totals"
	belowMinimum          = "a first deposit below the minimum deposit writes no CPA fact and leaves the first-deposit guard unset"
	consumedFactID        = "a fact whose id was consumed before writes nothing and is counted as a duplicate"

	ngrFold     = "GGR, bonus cost and chargeback facts fold into NGR and a RevShare fact for the delta"
	noCarryover = "without negative carryover a negative period floors RevShare at zero and the next period starts fresh"
	carryover   = "with negative carryover a deficit persists until positive NGR covers it"

	ancestorOverride  = "each ancestor is written an override fact for its percentage of the source's earnings, recomputed as a delta"
	bothMembers       = "a parent earns override at a brand where both hold a membership"
	seesDescendants   = "an affiliate sees every descendant and nothing above or beside them"
	outsideRefused    = "a request about an affiliate outside the subtree is refused"
	emailInvitation   = "an email invitation makes the sub active at once on the parent's plan unless it says otherwise"
	inviteLink        = "an invite link creates the sub pending under the parent for the parent to approve"
	parentActsOnSub   = "a parent's action on a sub applies within their own subtree and is audited as the parent's"
	closeClaims       = "a close claims every unclaimed payable fact up to the period's end for a pending payout"
	belowThreshold    = "a close below the affiliate's minimum payout threshold claims nothing and carries the facts forward"
	approveWithFigure = "approving a pending payout with another override figure writes the difference as an adjustment the payout claims"
	lateFactReverts   = "a late fact swept into an approved payout reverts it to pending and supersedes the approval"
	paidUnchanged     = "a late fact for a paid payout leaves it unchanged and is claimed by the next close"
	adjustmentSwept   = "a manager's adjustment is a payable fact, negative for a debit, swept by the next close"
	transitionRefused = "a payout transition the status machine does not allow is refused naming the current status"

	newLink        = "a new link carries a tag signed by the programme naming the affiliate, the link and the sub-ids"
	codeUnique     = "a bonus code is unique at a brand regardless of case"
	codeConcurrent = "two concurrent creations of the same bonus code text yield one code"
	codeRevoked    = "revoking a bonus code frees its text for later reuse"

	managersProvisioned = "adding, resetting or revoking a manager for a brand provisions them with the manager role and the brand's role and is audited"
	flagRaisedOnce      = "a fraud flag is raised once per player, brand and reason when a brand's thresholds are crossed"
	flagDismissed       = "dismissing a fraud flag tells the affiliate it was reviewed and dismissed"
	programmePage       = "a change to a brand's programme page is what new applicants see"
	readOnlyView        = "a manager's read-only view of an affiliate shows what the affiliate sees and changes nothing"
	markAllRead         = "reading notifications or marking all read marks them read for that affiliate only"

	toldByNotification = "an affiliate is told what happened to them by notification and by email, and a failed send never blocks the action"
)

// Plan creates a plan of the brand named `Plan {run}<suffix>`, remembered as `{plan<suffix>}` under
// the id the original names it by (`nameUUIDFromBytes("aff.plan:{brand}:{planName}")`).
func Plan(s *Session, suffix string, fields map[string]any) Response {
	name := "Plan " + s.Value("run") + suffix
	s.Remember("plan"+suffix, NameUUID("aff.plan:"+s.Brand()+":"+name))
	body := map[string]any{"planName": name}
	for k, v := range fields {
		body[k] = v
	}
	r := s.Want(s.Staff("create the plan"+suffix, http.MethodPost, "/commission-plans", body), http.StatusCreated)
	if id := r.Str("planId"); id != "" && id != s.Value("plan"+suffix) {
		s.t.Errorf("%s: the original named the plan %s, not %s", s.rec.Scenario, id, s.Value("plan"+suffix))
	}
	// Captured, not only remembered: the programme names a plan its own way, and a replay takes its id.
	s.Grab("plan"+suffix, "$.planId", r.Str("planId"))
	return r
}

// CPA is a CPA plan of 50.00 with a minimum deposit of 20.00.
var CPA = map[string]any{"planType": "CPA", "cpaAmount": "50.00", "minimumDepositThreshold": "20.00", "negativeCarryover": "false"}

// Account creates `who` as the backoffice does, active on `plan`, and captures its id as `{who}`.
func Account(s *Session, who, plan string, extra map[string]any) {
	body := map[string]any{"email": s.Email(who), "name": "Driver " + who, "planId": s.Value(plan)}
	for k, v := range extra {
		body[k] = v
	}
	r := s.Want(s.Staff("create the account: "+who, http.MethodPost, "/accounts", body), http.StatusCreated)
	s.Grab(who, "$.affiliateId", r.Str("affiliateId"))
}

// Link creates a tracking link for `who` in the portal and captures its tag as `{<who>Btag}`.
func Link(s *Session, who string) {
	r := s.Want(s.Portal("create a tracking link: "+who, who, who, http.MethodPost, "/tracking-links",
		map[string]any{"campaign": "autumn", "landingPath": "/welcome"}), http.StatusCreated)
	s.Grab(who+"Btag", "$.btag", r.Str("btag"))
}

// Register notifies the registration of `player` with the tag of `who`, as casino-service sends it.
func Register(s *Session, name, player, who string) Response {
	return s.Notify(name, "registration", map[string]any{"playerId": s.UUID(player), "btag": s.Value(who + "Btag"),
		"registeredAt": time.Now().UTC().Format(time.RFC3339), "country": "GB"})
}

// Deposit notifies a captured deposit of `amount` GBP by `player` under the transaction id `txn`.
func Deposit(s *Session, name, player, txn, amount string) Response {
	return s.Notify(name, "deposit", map[string]any{"playerId": s.Value(player), "transactionId": s.UUID(txn),
		"amount": amount, "currency": "GBP"})
}

// Attributed is an affiliate on `plan`, signed in, with a link and a registered player.
func Attributed(s *Session, who, plan, player string) {
	Account(s, who, plan, nil)
	s.SignIn(who)
	Link(s, who)
	s.WantField(s.Want(Register(s, "notify the registration of "+player, player, who), http.StatusOK), "affiliateId", s.Value(who))
}

// Defaults puts the brand's settings at the original's defaults with every fee at zero, so a fold is
// GGR less bonus cost and chargeback: the brand is shared with earlier runs.
func Defaults(s *Session) {
	s.WantOK(s.Setup("the brand's settings at the original's defaults, fees at zero", http.MethodPut,
		"/v1/affiliates/"+s.Brand()+"/settings", Settings, Staff))
}

// Settings are the original's default fraud thresholds and no fees.
var Settings = map[string]any{"bonusCostThreshold": "3", "chargebackThreshold": "2", "payoutCycleDay": "1",
	"adminFeePercent": "0", "paymentFeePercent": "0", "providerFeePercent": "0"}

func nonZero(v string) bool { return v != "" && strings.Trim(v, "0.") != "" }

// NotDriven are the scenarios of features/programme/ no recording of the original can show, and why
// (contracts/recording.md). Each is held by the port's own suites.
var NotDriven = map[string]string{
	"applying to a second brand leaves the first membership unchanged and shows the second as pending":            "the original keeps an account per brand and serves one brand per deployment",
	"revenue read for one brand folds only that brand's facts":                                                    "the original serves one brand per deployment",
	"revenue read with no brand filter lists each brand separately with totals per currency":                      "the original serves one brand per deployment and has no currency on a plan",
	"a suspended membership still records facts and writes no new payable fact while another brand is unaffected": "the original's commission engine never reads the account's status, and has one brand; not in the recording plan",
	"a status change outside the membership's transitions is refused":                                             "the original's PATCH accounts/{aff}/status writes any status (OperatorRoutes.scala:1999-2043); not in the recording plan",

	"only the GGR source the brand names folds and the other kind is recorded for reconciliation [daily]":        "the original has one GGR route and no GGR source setting",
	"only the GGR source the brand names folds and the other kind is recorded for reconciliation [rounds]":       "the original has one GGR route and no GGR source setting",
	"a GGR day from before the player's binding is recorded and folds nothing":                                   "the original does not compare a GGR fact's day with the registration",
	"a fact for a player without attribution at that brand is recorded as unattributed and writes no commission": "the original answers and records nothing for a player it has no registration for",
	"a completed withdrawal then its reversal raise and lower the player's withdrawal total, never below zero":   "attribution's recording [a withdrawal is requested] holds the original's withdrawal and reversal; the programme replays it translated",
	"a withdrawal requested or failed changes no total [withdrawal-requested]":                                   "the original is told of a withdrawal once, at approval, and never of a request as such",
	"a withdrawal requested or failed changes no total [withdrawal-failed]":                                      "nothing in the original tells the affiliate service of a failed withdrawal",
	"a reversal without a matching completed withdrawal is ignored":                                              "not in the recording plan; the original's guard (a marker without reversedAt) is held by the port's suites from its InternalRoutesSpec",

	"a postback is sent for a registration, first deposit, deposit or commission with the click id, payout amount and status substituted": "a postback is a call off the service to the affiliate's address, which the driver does not see",
	"a failed postback is retried with backoff for up to a day and its last outcome is visible":                                           "the original sends a postback once and swallows its outcome",
	toldByNotification + " [their application is received]":                                                                               "the original tells an applicant only by Customer.io email, which the local stack does not reach",
	toldByNotification + " [their application is approved]":                                                                               "the original tells only by Customer.io email, which the local stack does not reach",
	toldByNotification + " [their application is rejected]":                                                                               "the original tells only by Customer.io email, which the local stack does not reach",
	toldByNotification + " [their payout is paid]":                                                                                        "the original tells only by Customer.io email, which the local stack does not reach",
	toldByNotification + " [they are invited]":                                                                                            "the original tells only by Customer.io email, which the local stack does not reach",
	toldByNotification + " [they ask for a password reset]":                                                                               "the original tells only by Customer.io email, which the local stack does not reach",
	"a parent is told when a sub's application is pending approval":                                                                       "the original tells the parent through a TL capability (affiliate_notify_pending), not the service; the driver drives the service alone",

	"an affiliate's identity and payment fields are personal data under the affiliate":                                       "the original keeps identity and payment fields in plain twin properties",
	"a closed account's erasure waits for the retention hold and then erases identity while facts, payouts and audit remain": "the original has no account closure or erasure",
	"a player read in the programme shows a pseudonym, a country and figures, and nothing that names the player":             "the original's operator `players` read shows raw player ids; the port's suites hold the rule",
	"a player-closed fact makes the programme forget the pseudonym's country and click details and keep the amounts":         "the original has no player-closed notification",

	"a click fact from a brand is recorded against its link and raises the link's click count":                              "the programme serves no click route; attribution's landing recording holds the original's click",
	"a brand holding the programme's key document verifies a tag without calling the programme":                             "the original's tags are bare UUIDs and it publishes no key document",
	"a tag for a link on the programme's revocation list binds no one and the programme receives no fact for the affiliate": "the original's links have no revocation",
	"the legacy tag document maps each imported legacy tag to its affiliate and link during the transition period":          "the legacy tag document is the port's transition; the original has none",
	"the legacy tag document is withdrawn after the transition period":                                                      "the legacy tag document is the port's transition; the original has none",
	"a link's click count comes from click facts and never from a counter on the account":                                   "the original counts clicks in counters on the link and the revenue twin; attribution's landing recording holds the click",
	"a manager for one brand lists only that brand's memberships and facts":                                                 "the original serves one brand per deployment",
	"a manager's request naming a brand their roles do not cover is refused":                                                "the original's operator gate is role-only (OperatorRoutes.scala:370-372) and refuses no brand",
	"a manager for several brands reads revenue per brand and per currency":                                                 "the original serves one brand per deployment and has no currency on a plan",
	"a parent earns nothing at a brand where the sub has no membership":                                                     "the original serves one brand per deployment",
	"an operator's suspension of a sub stands over the parent":                                                              "the original keeps one status with no record of who suspended, so a parent may reactivate; not in the recording plan",
	"a parent chain that forms a cycle is visited once and the computation ends":                                            "the original refuses a cycle on write (409 parent_cycle), so none can be made through its routes",
	"facts in two currencies close as two payouts":                                                                          "the original's payout is one per affiliate and period whatever the currency (marked divergence 6)",
	"an export of approved payouts lists every figure and is audited as a read of payment details":                          "the original has no export route; the backoffice builds the file in the browser",
}

// NotDrivenFeatures are the features the original has nothing of, and why.
var NotDrivenFeatures = map[string]string{
	"hosting.feature": "the original serves one brand per deployment and has no hosted brands, webhooks or usage report",
	"import.feature":  "the import reads the original's twins, not its routes; it is checked by folding the imported ledger against the original's revenue",
}

// Scenarios is every recording, in contracts/recording.md's order.
func Scenarios() []Def {
	return []Def{
		{Driver: "apply, approve, reject, reapply", Name: approveOnDefaultPlan, Covers: []string{rejectedMayReapply, duplicateApplication},
			Requirement: "013/FR-003",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				apply := func(name, who string) Response {
					return s.Call(name, http.MethodPost, "/v1/affiliates/"+s.Brand()+"/apply",
						map[string]any{"email": s.Email(who), "name": "Driver " + who, "website": "https://example.com", "trafficSource": "seo"}, NoAuth)
				}
				r := s.WantField(s.Want(apply("apply", "affiliate"), http.StatusCreated), "status", "PENDING")
				s.Grab("affiliate", "$.affiliateId", r.Str("affiliateId"))
				s.StaffRow("wait for the application to be listed", "/accounts", "affiliateId", s.Value("affiliate"), "status", "PENDING")
				s.Vary("$", "the brand's accounts include earlier runs'")
				s.WantField(s.Want(apply("apply again while pending", "affiliate"), http.StatusConflict), "error", "email_already_registered")
				// The original has no default plan on a brand: the backoffice sends the plan it picks.
				s.WantOK(s.Staff("approve on the plan", http.MethodPost, "/accounts/"+s.Value("affiliate")+"/approve",
					map[string]any{"planId": s.Value("plan")}))
				s.WantField(s.Want(s.Staff("read the account", http.MethodGet, "/accounts/"+s.Value("affiliate"), nil), http.StatusOK), "status", "ACTIVE")
				s.StaffRow("wait for the approval to be listed", "/accounts", "affiliateId", s.Value("affiliate"), "status", "ACTIVE")
				s.Vary("$", "the brand's accounts include earlier runs'")
				s.WantField(s.Want(apply("apply again while active", "affiliate"), http.StatusConflict), "error", "email_already_registered")

				r = s.Want(apply("apply", "applicant"), http.StatusCreated)
				s.Grab("applicant", "$.affiliateId", r.Str("affiliateId"))
				s.WantOK(s.Staff("reject", http.MethodPost, "/accounts/"+s.Value("applicant")+"/reject",
					map[string]any{"reason": "traffic source not accepted"}))
				s.StaffRow("wait for the rejection to be listed", "/accounts", "affiliateId", s.Value("applicant"), "status", "REJECTED")
				s.Vary("$", "the brand's accounts include earlier runs'")
				r = s.WantField(s.Want(apply("apply again after rejection", "applicant"), http.StatusCreated), "status", "PENDING")
				s.WantField(r, "affiliateId", s.Value("applicant")) // the original reuses the rejected account
			}},
		{Driver: "plans", Name: planChanged, Requirement: "013/FR-006",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				s.WantField(s.Want(s.Staff("create the plan again", http.MethodPost, "/commission-plans",
					map[string]any{"planName": "Plan " + s.Value("run"), "planType": "CPA", "cpaAmount": "50.00"}), http.StatusConflict),
					"error", "plan_already_exists")
				s.WantOK(s.Staff("change the plan", http.MethodPatch, "/commission-plans/"+s.Value("plan"),
					map[string]any{"cpaAmount": "75.00", "minimumDepositThreshold": "25.00"}))
				s.StaffRow("list the plans", "/commission-plans", "planName", "Plan "+s.Value("run"), "cpaAmount", "75.00")
				s.Vary("$", "the brand's plans include earlier runs'")
			}},
		{Driver: "cpa once", Name: firstDepositCPA,
			Covers:      []string{registrationRecorded, registrationDuplicate, laterDeposit, belowMinimum, consumedFactID},
			Requirement: "013/FR-013",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Attributed(s, "affiliate", "plan", "player")
				s.WantField(s.Want(Register(s, "notify the registration again", "player", "affiliate"), http.StatusOK), "duplicate", "true")
				// The original answers a deposit below the minimum `{"ok":true}`, and a first deposit at the
				// minimum or above with the affiliate it pays.
				s.WantField(s.Want(Deposit(s, "notify a deposit below the minimum", "player", "txn1", "10.00"), http.StatusOK), "affiliateId", "")
				s.WantField(s.Want(Deposit(s, "notify the first deposit at the minimum or above", "player", "txn2", "50.00"), http.StatusOK), "affiliateId", s.Value("affiliate"))
				s.WantField(s.Want(Deposit(s, "notify the same deposit again", "player", "txn2", "50.00"), http.StatusOK), "duplicate", "true")
				s.Want(Deposit(s, "notify a later deposit", "player", "txn3", "30.00"), http.StatusOK)
				s.Settled("read revenue for the period", http.MethodGet, "/v1/my/"+s.Value("affiliate")+"/revenue?period="+s.Value("period"),
					As("affiliate"), func(r Response) bool { return r.Status == http.StatusOK && r.Field("ftdCount") == "1" })
				s.Want(s.Portal("read the period's breakdown", "affiliate", "affiliate", http.MethodGet, "/revenue/"+s.Value("period")+"/breakdown", nil), http.StatusOK)
			}},
		{Driver: "revshare and carryover", Name: ngrFold, Covers: []string{noCarryover, carryover}, Requirement: "013/FR-014",
			Run: func(t testing.TB, s *Session) {
				Defaults(s)
				Plan(s, "", map[string]any{"planType": "REVSHARE", "revSharePercent": "25", "negativeCarryover": "false"})
				Plan(s, "Carryover", map[string]any{"planType": "REVSHARE", "revSharePercent": "25", "negativeCarryover": "true"})
				for _, a := range []struct{ who, plan, player string }{{"affiliate", "plan", "player"}, {"carrier", "planCarryover", "carriedPlayer"}} {
					Attributed(s, a.who, a.plan, a.player)
					// A loss last period, then a win this period, less a bonus cost and a chargeback:
					// 270.00 NGR this period, 170.00 over both.
					s.Want(s.Notify("notify last period's GGR: "+a.who, "ggr", map[string]any{"playerId": s.Value(a.player),
						"period": s.Value("previousPeriod"), "eventId": s.UUID(a.who + "Round1"), "ggr": "-100.00", "wageredAmount": "100.00",
						"occurredAt": s.Value("previousPeriod") + "-15T12:00:00Z"}), http.StatusOK)
					s.Want(s.Notify("notify this period's GGR: "+a.who, "ggr", map[string]any{"playerId": s.Value(a.player),
						"period": s.Value("period"), "eventId": s.UUID(a.who + "Round2"), "ggr": "300.00", "wageredAmount": "900.00"}), http.StatusOK)
					s.Want(s.Notify("notify a bonus cost: "+a.who, "bonus-cost", map[string]any{"playerId": s.Value(a.player),
						"transactionId": s.UUID(a.who + "Bonus"), "amount": "20.00", "currency": "GBP"}), http.StatusOK)
					s.Want(s.Notify("notify a chargeback: "+a.who, "chargeback", map[string]any{"playerId": s.Value(a.player),
						"transactionId": s.UUID(a.who + "Chargeback"), "amount": "10.00", "currency": "GBP"}), http.StatusOK)
					s.Settled("read revenue for the period: "+a.who, http.MethodGet, "/v1/my/"+s.Value(a.who)+"/revenue?period="+s.Value("period"),
						As(a.who), func(r Response) bool { return r.Status == http.StatusOK && nonZero(r.Field("revShareCommission")) })
					s.Want(s.Portal("read the period's deductions: "+a.who, a.who, a.who, http.MethodGet, "/deductions/"+s.Value("period"), nil), http.StatusOK)
				}
			}},
		{Driver: "network override", Name: ancestorOverride, Covers: []string{bothMembers, seesDescendants, outsideRefused},
			Requirement: "013/FR-015",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Account(s, "parent", "plan", nil)
				s.SignIn("parent")
				r := s.Want(s.Portal("invite a sub by email", "parent", "parent", http.MethodPost, "/sub-affiliates/invite",
					map[string]any{"email": s.Email("sub"), "overridePercent": "10"}), http.StatusCreated)
				s.Grab("sub", "$.affiliateId", r.Str("affiliateId"))
				Account(s, "outsider", "plan", nil)
				s.SignIn("sub")
				Link(s, "sub")
				s.WantField(s.Want(Register(s, "notify the registration of the sub's player", "player", "sub"), http.StatusOK), "affiliateId", s.Value("sub"))
				s.WantField(s.Want(Deposit(s, "notify the sub's player's first deposit", "player", "txn", "50.00"), http.StatusOK), "affiliateId", s.Value("sub"))
				s.Settled("read the parent's revenue for the period", http.MethodGet, "/v1/my/"+s.Value("parent")+"/revenue?period="+s.Value("period"),
					As("parent"), func(r Response) bool { return r.Status == http.StatusOK && nonZero(r.Field("overrideCommission")) })
				s.Want(s.Portal("read the parent's network", "parent", "parent", http.MethodGet, "/network?maxDepth=3&period="+s.Value("period"), nil), http.StatusOK)
				s.Want(s.Portal("read the sub as its parent", "parent", "parent", http.MethodGet, "/network/"+s.Value("sub"), nil), http.StatusOK)
				s.Want(s.Portal("read the sub's revenue as its parent", "parent", "sub", http.MethodGet, "/revenue?period="+s.Value("period"), nil), http.StatusOK)
				s.WantRefused(s.Portal("read the parent as the sub", "sub", "parent", http.MethodGet, "/account", nil))
				s.WantRefused(s.Portal("read an outsider as the parent", "parent", "outsider", http.MethodGet, "/account", nil))
				s.WantRefused(s.Portal("read the outsider in the parent's network", "parent", "parent", http.MethodGet, "/network/"+s.Value("outsider"), nil))
			}},
		{Driver: "invite and join", Name: emailInvitation, Covers: []string{inviteLink, parentActsOnSub}, Requirement: "013/FR-027",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Account(s, "parent", "plan", nil)
				s.SignIn("parent")
				r := s.Want(s.Portal("invite a sub by email", "parent", "parent", http.MethodPost, "/sub-affiliates/invite",
					map[string]any{"email": s.Email("sub"), "overridePercent": "5"}), http.StatusCreated)
				s.Grab("sub", "$.affiliateId", r.Str("affiliateId"))
				r = s.Settled("read the invited sub", http.MethodGet, "/v1/my/"+s.Value("parent")+"/network/"+s.Value("sub"), As("parent"),
					func(r Response) bool { return r.Status == http.StatusOK && r.Str("status") == "ACTIVE" })
				s.WantField(r, "planId", s.Value("plan"))
				r = s.Want(s.Portal("create an invite link", "parent", "parent", http.MethodPost, "/invite-links", map[string]any{}), http.StatusCreated)
				s.Grab("inviteLink", "$.linkId", r.Str("linkId"))
				s.Grab("inviteToken", "$.token", r.Str("token"))
				join := "/v1/join/" + s.Value("inviteToken") + "?brandId=" + s.Brand()
				// The original finds the link by scanning its namespace, and a join spends its one attempt
				// per email before the scan: the preview is read until the link is found.
				s.Settled("open the invite link", http.MethodGet, join, NoAuth, func(r Response) bool { return r.Status == http.StatusOK })
				r = s.WantField(s.Want(s.Call("join through the invite link", http.MethodPost, join,
					map[string]any{"email": s.Email("joiner"), "name": "Driver joiner"}, NoAuth), http.StatusCreated), "status", "PENDING")
				s.Grab("joiner", "$.affiliateId", r.Str("affiliateId"))
				s.WantOK(s.Portal("approve the joiner as its parent", "parent", "parent", http.MethodPost, "/network/"+s.Value("joiner")+"/approve", map[string]any{}))
				s.Settled("read the joiner", http.MethodGet, "/v1/my/"+s.Value("parent")+"/network/"+s.Value("joiner"), As("parent"),
					func(r Response) bool { return r.Status == http.StatusOK && r.Str("status") == "ACTIVE" })
				s.Want(s.Staff("read the joiner's audit", http.MethodGet, "/accounts/"+s.Value("joiner")+"/audit", nil), http.StatusOK)
			}},
		{Driver: "close and pay", Name: closeClaims,
			Covers: []string{belowThreshold, approveWithFigure, lateFactReverts, paidUnchanged, adjustmentSwept,
				transitionRefused + " [paid]", transitionRefused + " [rejected]"},
			Requirement: "013/FR-018",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Account(s, "affiliate", "plan", map[string]any{"minimumPayoutThreshold": "100.00"})
				Account(s, "rejected", "plan", map[string]any{"minimumPayoutThreshold": "10.00"})
				// The original names a payout by affiliate and period only (marked divergence 6).
				s.Remember("payout", NameUUID("aff.payout:"+s.Value("affiliate")+":"+s.Value("period")))
				s.Remember("rejectedPayout", NameUUID("aff.payout:"+s.Value("rejected")+":"+s.Value("period")))
				s.Remember("nextPayout", NameUUID("aff.payout:"+s.Value("affiliate")+":"+s.Value("nextPeriod")))
				credit := func(name, who, amount string) {
					s.Want(s.Staff(name, http.MethodPost, "/accounts/"+s.Value(who)+"/adjustments", map[string]any{"type": "CREDIT",
						"amount": amount, "reason": "driver credit", "actorId": "driver", "period": s.Value("period")}), http.StatusCreated)
				}
				closing := func(name, period string) {
					s.Want(s.Staff(name, http.MethodPost, "/payouts/close?period="+s.Value(period), nil), http.StatusOK)
					s.Vary("$.created", "the brand's other affiliates from earlier runs")
					s.Vary("$.payouts", "the brand's other affiliates from earlier runs")
				}
				payouts := func(name, period string) Response {
					r := s.Want(s.Staff(name, http.MethodGet, "/payouts?period="+s.Value(period), nil), http.StatusOK)
					s.Vary("$", "the brand's payouts include earlier runs'")
					return r
				}
				rows := func(name, period, want string) {
					s.StaffRow(name, "/payouts?period="+s.Value(period), "affiliateId", s.Value("affiliate"), "status", want)
					s.Vary("$", "the brand's payouts include earlier runs'")
				}
				status := func(name, payout string, body map[string]any, want int) Response {
					return s.Want(s.Staff(name, http.MethodPatch, "/payouts/"+s.Value(payout)+"/status", body), want)
				}

				credit("credit 40.00", "affiliate", "40.00")
				credit("credit 30.00 to the affiliate to be rejected", "rejected", "30.00")
				closing("close the period below the affiliate's threshold", "period")
				s.WantNoRow(payouts("list the period's payouts below the threshold", "period"), "affiliateId", s.Value("affiliate"))

				status("reject the other affiliate's payout", "rejectedPayout", map[string]any{"status": "REJECTED", "reason": "driver rejection", "actorId": "driver"}, http.StatusOK)
				r := status("pay the rejected payout", "rejectedPayout", map[string]any{"status": "PAID", "actorId": "driver"}, http.StatusConflict)
				if !strings.Contains(r.Str("message"), "REJECTED") {
					s.t.Errorf("%s: the refusal does not name the current status: %s", s.rec.Scenario, string(r.Raw))
				}

				credit("credit 80.00", "affiliate", "80.00")
				closing("close the period above the threshold", "period")
				rows("list the period's payouts above the threshold", "period", "PENDING")

				// The original rewrites the payout's figure and total and writes no fact (marked divergence 14).
				status("approve with another override figure", "payout", map[string]any{"status": "APPROVED", "actorId": "driver", "overrideCommission": 5}, http.StatusOK)
				credit("a late credit of 10.00", "affiliate", "10.00")
				closing("close the period after the approval", "period")
				rows("list the period's payouts after the late credit", "period", "PENDING")

				status("approve again", "payout", map[string]any{"status": "APPROVED", "actorId": "driver"}, http.StatusOK)
				status("pay", "payout", map[string]any{"status": "PAID", "actorId": "driver"}, http.StatusOK)
				// The original refuses a target outside APPROVED, REJECTED and PAID before it reads the
				// payout, so it answers 400 without the current status.
				status("move the paid payout back to pending", "payout", map[string]any{"status": "PENDING", "actorId": "driver"}, http.StatusBadRequest)
				credit("a late credit of 120.00 after payment", "affiliate", "120.00")
				closing("close the period after payment", "period")
				rows("list the period's payouts after payment", "period", "PAID")
				closing("close the next period", "nextPeriod")
				rows("list the next period's payouts", "nextPeriod", "PENDING")
			}},
		{Driver: "links and codes", Name: codeUnique, Covers: []string{newLink, codeConcurrent, codeRevoked}, Requirement: "013/FR-025",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Account(s, "affiliate", "plan", nil)
				s.SignIn("affiliate")
				// The original's tag is a bare UUID, unsigned.
				Link(s, "affiliate")
				s.Want(s.Portal("list the tracking links", "affiliate", "affiliate", http.MethodGet, "/tracking-links", nil), http.StatusOK)
				code := "AUT" + strings.ToUpper(s.Value("run"))
				s.Remember("code", code)
				s.Remember("codeLower", strings.ToLower(code))
				s.Remember("raceCode", "RACE"+strings.ToUpper(s.Value("run")))
				create := func(name, text string) Response {
					return s.Portal(name, "affiliate", "affiliate", http.MethodPost, "/bonus-codes", map[string]any{"code": text, "btag": s.Value("affiliateBtag")})
				}
				r := s.WantField(s.Want(create("create a bonus code", code), http.StatusCreated), "code", code)
				s.Grab("codeId", "$.codeId", r.Str("codeId"))
				s.WantField(s.Want(create("create the same code in lower case", strings.ToLower(code)), http.StatusConflict), "error", "code_already_exists")
				race := s.Race("create the same code twice at once", 2, http.MethodPost, "/v1/my/"+s.Value("affiliate")+"/bonus-codes",
					map[string]any{"code": s.Value("raceCode"), "btag": s.Value("affiliateBtag")}, As("affiliate"))
				s.Want(race[0], http.StatusCreated)
				s.Want(race[1], http.StatusConflict)
				s.WantOK(s.Portal("revoke the bonus code", "affiliate", "affiliate", http.MethodDelete, "/bonus-codes/"+s.Value("codeId"), nil))
				s.Want(create("create the revoked code's text again", code), http.StatusCreated)
				s.Vary("$.codeId", "the original names a code by its text, so the id may repeat; the port mints its own")
				s.Want(s.Portal("list the bonus codes", "affiliate", "affiliate", http.MethodGet, "/bonus-codes", nil), http.StatusOK)
			}},
		{Driver: "managers", Name: managersProvisioned, Requirement: "013/FR-031",
			Run: func(t testing.TB, s *Session) {
				// The original has no route that revokes a manager: adding and resetting are recorded.
				r := s.Want(s.Staff("add a manager", http.MethodPost, "/managers", map[string]any{"email": s.Email("manager"), "name": "Driver manager"}), http.StatusCreated)
				s.Grab("manager", "$.keycloakUserId", r.Str("keycloakUserId"))
				s.WantField(s.Want(s.Staff("add the same manager again", http.MethodPost, "/managers",
					map[string]any{"email": s.Email("manager"), "name": "Driver manager"}), http.StatusConflict), "error", "user_already_exists")
				r = s.Want(s.Staff("list the managers", http.MethodGet, "/managers", nil), http.StatusOK)
				s.Vary("$", "the brand's managers include earlier runs'")
				s.WantRow(r, "keycloakUserId", s.Value("manager"), "", "")
				s.Want(s.Staff("reset the manager's password", http.MethodPost, "/managers/"+s.Value("manager")+"/reset-password", map[string]any{}), http.StatusNoContent)
			}},
		{Driver: "fraud flags", Name: flagRaisedOnce, Covers: []string{flagDismissed}, Requirement: "013/FR-032",
			Run: func(t testing.TB, s *Session) {
				Defaults(s)
				Plan(s, "", CPA)
				Attributed(s, "affiliate", "plan", "player")
				// The original names a flag by brand, player and reason, and its reason carries the count.
				s.Remember("flag", NameUUID("aff.fraud:"+s.Brand()+":"+s.Value("player")+":3+ bonus deductions"))
				for i, txn := range []string{"bonus1", "bonus2", "bonus3"} {
					s.Want(s.Notify("notify bonus cost "+string(rune('1'+i)), "bonus-cost", map[string]any{"playerId": s.Value("player"),
						"transactionId": s.UUID(txn), "amount": "10.00", "currency": "GBP"}), http.StatusOK)
				}
				r := s.Settled("list the fraud flags", http.MethodGet, "/v1/affiliates/"+s.Brand()+"/fraud-flags", Staff,
					func(r Response) bool {
						row, _ := r.Find("playerId", s.Value("player"))
						return r.Status == http.StatusOK && row != nil
					})
				s.Vary("$", "the brand's flags include earlier runs'")
				s.WantRow(r, "playerId", s.Value("player"), "reason", "3+ bonus deductions")
				s.WantOK(s.Staff("dismiss the flag", http.MethodPatch, "/fraud-flags/"+s.Value("flag")+"/dismiss",
					map[string]any{"reason": "reviewed: a promotion", "operatorId": "driver"}))
				s.Settled("read the affiliate's notifications", http.MethodGet, "/v1/my/"+s.Value("affiliate")+"/notifications", As("affiliate"),
					func(r Response) bool {
						row, _ := r.Find("type", "FRAUD_DISMISSED")
						return r.Status == http.StatusOK && row != nil
					})
			}},
		{Driver: "notes, messages, creatives, settings, programme page", Name: programmePage, Requirement: "013/FR-034",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Account(s, "affiliate", "plan", nil)
				s.WantOK(s.Staff("add a note", http.MethodPost, "/accounts/"+s.Value("affiliate")+"/notes", map[string]any{"text": "Driver note", "authorId": "driver"}))
				s.Want(s.Staff("list the notes", http.MethodGet, "/accounts/"+s.Value("affiliate")+"/notes", nil), http.StatusOK)
				s.Want(s.Staff("read the audit", http.MethodGet, "/accounts/"+s.Value("affiliate")+"/audit", nil), http.StatusOK)
				s.Want(s.Staff("read the settings", http.MethodGet, "/settings", nil), http.StatusOK)
				s.WantOK(s.Staff("change the settings", http.MethodPut, "/settings", Settings))
				s.Want(s.Staff("read the programme page", http.MethodGet, "/programme", nil), http.StatusOK)
				s.WantOK(s.Staff("change the programme page", http.MethodPut, "/programme", map[string]any{"payoutCycleDays": "30", "minimumPayoutAmount": "50.00"}))
				s.WantField(s.Want(s.Staff("read the changed programme page", http.MethodGet, "/programme", nil), http.StatusOK), "minimumPayoutAmount", "50.00")
				s.WantOK(s.Staff("message the affiliate", http.MethodPost, "/messages", map[string]any{"body": "Driver message", "targetAffiliateId": s.Value("affiliate")}))
				r := s.Want(s.Staff("add a creative", http.MethodPost, "/creatives", map[string]any{"name": "Banner " + s.Value("run"),
					"creativeType": "banner", "fileUrl": "https://example.com/creatives/banner-728x90.png", "dimensions": "728x90", "description": "Driver banner"}), http.StatusCreated)
				s.Grab("creative", "$.creativeId", r.Str("creativeId"))
				s.Want(s.Staff("list the creatives", http.MethodGet, "/creatives", nil), http.StatusOK)
				s.Vary("$", "the brand's creatives include earlier runs'")
				s.WantOK(s.Staff("delete the creative", http.MethodDelete, "/creatives/"+s.Value("creative"), nil))
				// The original's application reads nothing of the programme page.
				r = s.Want(s.Call("a new applicant applies", http.MethodPost, "/v1/affiliates/"+s.Brand()+"/apply",
					map[string]any{"email": s.Email("applicant"), "name": "Driver applicant", "termsVersion": "2026-11"}, NoAuth), http.StatusCreated)
				s.Grab("applicant", "$.affiliateId", r.Str("affiliateId"))
			}},
		{Driver: "read-only view", Name: readOnlyView, Requirement: "013/FR-033",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				Account(s, "affiliate", "plan", nil)
				view := "/accounts/" + s.Value("affiliate") + "/view/"
				// The dashboard and stats take the period as a query (OperatorRoutes.scala:2601-2640).
				for _, page := range []string{"dashboard?period=" + s.Value("period"), "stats?period=" + s.Value("period"),
					"commission/" + s.Value("period"), "links", "payouts", "notifications"} {
					s.Want(s.Staff("view the affiliate's "+page, http.MethodGet, view+page, nil), http.StatusOK)
				}
			}},
		{Driver: "notifications", Name: markAllRead, Requirement: "013/FR-035",
			Run: func(t testing.TB, s *Session) {
				Plan(s, "", CPA)
				for _, who := range []string{"affiliate", "other"} {
					Account(s, who, "plan", nil)
					s.WantOK(s.Setup("a message to "+who, http.MethodPost, "/v1/affiliates/"+s.Brand()+"/messages",
						map[string]any{"body": "Driver message", "targetAffiliateId": s.Value(who)}, Staff))
					s.SignIn(who)
				}
				s.Want(s.Portal("read the notifications", "affiliate", "affiliate", http.MethodGet, "/notifications", nil), http.StatusOK)
				s.WantField(s.Want(s.Portal("mark all read", "affiliate", "affiliate", http.MethodPatch, "/notifications/read-all", map[string]any{}), http.StatusOK), "marked", "1")
				s.Want(s.Portal("read the notifications again", "affiliate", "affiliate", http.MethodGet, "/notifications", nil), http.StatusOK)
				s.Want(s.Portal("read the other affiliate's notifications", "other", "other", http.MethodGet, "/notifications", nil), http.StatusOK)
			}},
	}
}
