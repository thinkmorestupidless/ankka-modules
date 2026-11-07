package programmedriver

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

const (
	stubBrand  = "spinvibe"
	stubSecret = "s3cret"
	staffTok   = "staff-tok-eyJ"
	adminTok   = "admin-tok-eyJ"
)

// stub answers as the original's affiliate service and its Keycloak do, as far as the recordings
// need: accounts with their logins, applications, plans, links, bonus codes upper-cased and claimed
// once, invitations, the ancestor check of the portal, notifications, a CPA count, an override on the
// parent, three bonus costs raising a flag, and the close and the payout's status machine.
type stub struct {
	mu       sync.Mutex
	accounts map[string]map[string]string // affiliate id → fields
	byEmail  map[string]string
	users    map[string]string // realm user id → email
	plans    map[string]bool
	links    map[string]string // btag → affiliate
	codes    map[string]string // upper-cased code → code id, while active
	invites  map[string]string // token → parent
	regs     map[string]string // player → affiliate
	txns     map[string]bool
	ftd      map[string]bool
	ftdCount map[string]int
	override map[string]bool
	bonuses  map[string]int
	flags    []map[string]string
	notes    map[string][]map[string]string // affiliate → notifications
	open     map[string]float64             // affiliate → unclaimed credit
	payouts  map[string]map[string]string   // payout id → fields
	managers map[string]bool
	secrets  []string
	auths    []string
}

func newStub() *stub {
	return &stub{accounts: map[string]map[string]string{}, byEmail: map[string]string{}, users: map[string]string{},
		plans: map[string]bool{}, links: map[string]string{}, codes: map[string]string{}, invites: map[string]string{},
		regs: map[string]string{}, txns: map[string]bool{}, ftd: map[string]bool{}, ftdCount: map[string]int{},
		override: map[string]bool{}, bonuses: map[string]int{}, notes: map[string][]map[string]string{},
		open: map[string]float64{}, payouts: map[string]map[string]string{}, managers: map[string]bool{}}
}

func (o *stub) create(email, status, parent string) string {
	id := NewUUID()
	o.accounts[id] = map[string]string{"affiliateId": id, "email": email, "status": status, "parentAffiliateId": parent}
	o.byEmail[email] = id
	o.users["kc-"+id] = email
	return id
}

// within is whether target is caller or one of caller's descendants.
func (o *stub) within(caller, target string) bool {
	for at := target; at != ""; at = o.accounts[at]["parentAffiliateId"] {
		if at == caller {
			return true
		}
	}
	return false
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	raw, _ := io.ReadAll(r.Body)
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	field := func(k string) string { return str(body[k]) }
	answer := func(status int, v any) {
		w.WriteHeader(status)
		if v != nil {
			_ = json.NewEncoder(w).Encode(v)
		}
	}
	seg := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	path, m := r.URL.Path, r.Method
	if a := r.Header.Get("Authorization"); a != "" {
		o.auths = append(o.auths, a)
	}

	switch {
	// Keycloak.
	case strings.HasPrefix(path, "/admin/realms/eitheror/users"):
		if r.Header.Get("Authorization") != "Bearer "+adminTok {
			answer(401, nil)
			return
		}
		if m == http.MethodGet {
			for id, email := range o.users {
				if email == r.URL.Query().Get("email") {
					answer(200, []map[string]string{{"id": id}})
					return
				}
			}
			answer(200, []any{})
			return
		}
		answer(204, nil)
	case path == "/realms/eitheror/protocol/openid-connect/token":
		form, _ := url.ParseQuery(string(raw)) // the body is read above
		if form.Get("client_id") != "affiliate-portal" || form.Get("password") == "" {
			answer(401, nil)
			return
		}
		answer(200, map[string]string{"access_token": "aff-tok-eyJ" + form.Get("username")})

	// Internal.
	case len(seg) == 6 && seg[0] == "internal" && seg[4] == "notify":
		o.secrets = append(o.secrets, r.Header.Get("X-Internal-Secret"))
		player := field("playerId")
		switch seg[5] {
		case "registration":
			if _, ok := o.regs[player]; ok {
				answer(200, map[string]any{"ok": true, "duplicate": true})
				return
			}
			o.regs[player] = o.links[field("btag")]
			answer(200, map[string]any{"ok": true, "affiliateId": o.regs[player]})
		case "deposit":
			aff, txn := o.regs[player], field("transactionId")
			amount, _ := strconv.ParseFloat(field("amount"), 64)
			switch {
			case o.txns[txn]:
				answer(200, map[string]any{"ok": true, "duplicate": true})
			case amount < 20:
				o.txns[txn] = true
				answer(200, map[string]any{"ok": true}) // as the original answered on kind, 2026-11-07
			case !o.ftd[player]:
				o.txns[txn], o.ftd[player] = true, true
				o.ftdCount[aff]++
				if p := o.accounts[aff]["parentAffiliateId"]; p != "" {
					o.override[p] = true
				}
				answer(200, map[string]any{"ok": true, "affiliateId": aff})
			default:
				o.txns[txn] = true
				answer(200, map[string]any{"ok": true, "affiliateId": aff})
			}
		case "bonus-cost":
			if field("transactionId") == "" {
				answer(400, map[string]any{"error": "transactionId is required"})
				return
			}
			o.bonuses[player]++
			if n := o.bonuses[player]; n >= 3 {
				o.flags = append(o.flags, map[string]string{"playerId": player, "affiliateId": o.regs[player],
					"reason": fmt.Sprintf("%d+ bonus deductions", n), "dismissed": "false"})
			}
			answer(200, map[string]any{"ok": true})
		default:
			answer(200, map[string]any{"ok": true})
		}

	// Public.
	case len(seg) == 4 && seg[0] == "v1" && seg[1] == "affiliates" && seg[3] == "apply":
		if id, ok := o.byEmail[field("email")]; ok {
			if o.accounts[id]["status"] != "REJECTED" {
				answer(409, map[string]any{"error": "email_already_registered"})
				return
			}
			o.accounts[id]["status"] = "PENDING"
			answer(201, map[string]any{"affiliateId": id, "status": "PENDING"})
			return
		}
		answer(201, map[string]any{"affiliateId": o.create(field("email"), "PENDING", ""), "status": "PENDING"})
	case len(seg) == 3 && seg[0] == "v1" && seg[1] == "join":
		parent, ok := o.invites[seg[2]]
		switch {
		case !ok || r.URL.Query().Get("brandId") != stubBrand:
			answer(404, map[string]any{"error": "link_not_found_or_expired"})
		case m == http.MethodGet:
			answer(200, map[string]any{"affiliateName": "Driver parent", "brandId": stubBrand})
		default:
			answer(201, map[string]any{"affiliateId": o.create(field("email"), "PENDING", parent), "status": "PENDING"})
		}

	// Portal.
	case len(seg) >= 4 && seg[0] == "v1" && seg[1] == "my":
		caller := o.byEmail[strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer aff-tok-eyJ")]
		target, rest := seg[2], strings.Join(seg[3:], "/")
		if caller == "" || !o.within(caller, target) {
			answer(404, map[string]any{"error": "account_not_found"})
			return
		}
		switch {
		case rest == "revenue":
			rev := map[string]any{"ftdCount": strconv.Itoa(o.ftdCount[target]), "revShareCommission": "67.50", "overrideCommission": "0.00"}
			if o.override[target] {
				rev["overrideCommission"] = "5.00"
			}
			answer(200, map[string]any{"period": r.URL.Query().Get("period"), "revenue": rev})
		case rest == "tracking-links" && m == http.MethodPost:
			btag := NewUUID()
			o.links[btag] = target
			answer(201, map[string]any{"btag": btag})
		case rest == "bonus-codes" && m == http.MethodPost:
			code := strings.ToUpper(field("code"))
			if _, taken := o.codes[code]; taken {
				answer(409, map[string]any{"error": "code_already_exists"})
				return
			}
			o.codes[code] = NameUUID("aff.code:" + stubBrand + ":" + code)
			answer(201, map[string]any{"codeId": o.codes[code], "code": code, "btag": field("btag"), "status": "ACTIVE"})
		case strings.HasPrefix(rest, "bonus-codes/") && m == http.MethodDelete:
			for code, id := range o.codes {
				if id == seg[4] {
					delete(o.codes, code)
				}
			}
			answer(200, map[string]any{"ok": true})
		case rest == "sub-affiliates/invite":
			id := o.create(field("email"), "ACTIVE", target)
			o.accounts[id]["planId"] = o.accounts[target]["planId"]
			answer(201, map[string]any{"affiliateId": id, "email": field("email")})
		case rest == "invite-links":
			token := strings.ReplaceAll(NewUUID(), "-", "")
			o.invites[token] = target
			answer(201, map[string]any{"linkId": NewUUID(), "token": token, "createdAt": time.Now().UTC().Format(time.RFC3339)})
		case strings.HasPrefix(rest, "network/"):
			sub := seg[4]
			if sub == target || !o.within(target, sub) {
				answer(404, map[string]any{"error": "account_not_found"})
				return
			}
			if strings.HasSuffix(rest, "/approve") {
				o.accounts[sub]["status"] = "ACTIVE"
			}
			answer(200, o.accounts[sub])
		case rest == "notifications":
			answer(200, o.notes[target])
		case rest == "notifications/read-all":
			n := 0
			for _, note := range o.notes[target] {
				if note["read"] == "false" {
					note["read"], n = "true", n+1
				}
			}
			answer(200, map[string]any{"marked": n})
		default:
			answer(200, map[string]any{"affiliateId": target})
		}

	// Operator.
	case len(seg) >= 4 && seg[0] == "v1" && seg[1] == "affiliates":
		if r.Header.Get("Authorization") != "Bearer "+staffTok {
			answer(401, nil)
			return
		}
		rest := strings.Join(seg[3:], "/")
		switch {
		case rest == "commission-plans" && m == http.MethodPost:
			if o.plans[field("planName")] {
				answer(409, map[string]any{"error": "plan_already_exists"})
				return
			}
			o.plans[field("planName")] = true
			answer(201, map[string]any{"planId": NameUUID("aff.plan:" + stubBrand + ":" + field("planName"))})
		case rest == "commission-plans":
			var rows []map[string]string
			for name := range o.plans {
				rows = append(rows, map[string]string{"planName": name, "cpaAmount": "75.00"})
			}
			answer(200, rows)
		case rest == "accounts" && m == http.MethodGet:
			var rows []map[string]any
			for id, f := range o.accounts {
				rows = append(rows, map[string]any{"properties": map[string]string{"affiliateId": id, "status": f["status"]}, "twinId": id})
			}
			answer(200, rows)
		case rest == "accounts" && m == http.MethodPost:
			id := o.create(field("email"), "ACTIVE", field("parentAffiliateId"))
			o.accounts[id]["planId"], o.accounts[id]["minimumPayoutThreshold"] = field("planId"), field("minimumPayoutThreshold")
			answer(201, map[string]any{"affiliateId": id})
		case len(seg) == 5 && seg[3] == "accounts":
			answer(200, o.accounts[seg[4]])
		case len(seg) == 6 && seg[3] == "accounts" && seg[5] == "approve":
			o.accounts[seg[4]]["status"], o.accounts[seg[4]]["planId"] = "ACTIVE", field("planId")
			answer(200, map[string]any{"ok": true})
		case len(seg) == 6 && seg[3] == "accounts" && seg[5] == "reject":
			o.accounts[seg[4]]["status"] = "REJECTED"
			answer(200, map[string]any{"ok": true})
		case len(seg) == 6 && seg[3] == "accounts" && seg[5] == "adjustments":
			amount, _ := strconv.ParseFloat(field("amount"), 64)
			o.open[seg[4]] += amount
			answer(201, map[string]any{"ok": true})
		case len(seg) == 6 && seg[3] == "accounts" && seg[5] == "notes" && m == http.MethodPost:
			answer(201, map[string]any{"ok": true})
		case rest == "payouts/close":
			period := r.URL.Query().Get("period")
			var made []map[string]string
			ids := make([]string, 0, len(o.open))
			for aff := range o.open {
				ids = append(ids, aff)
			}
			sort.Strings(ids)
			for _, aff := range ids {
				id := NameUUID("aff.payout:" + aff + ":" + period)
				p := o.payouts[id]
				if p != nil && p["status"] == "PAID" || o.open[aff] == 0 {
					continue
				}
				claimed := 0.0
				if p != nil {
					claimed, _ = strconv.ParseFloat(p["totalPayable"], 64)
				}
				threshold, _ := strconv.ParseFloat(o.accounts[aff]["minimumPayoutThreshold"], 64)
				if claimed+o.open[aff] < threshold {
					continue
				}
				if p == nil {
					p = map[string]string{"affiliateId": aff, "period": period}
					o.payouts[id] = p
				}
				p["status"], p["totalPayable"] = "PENDING", fmt.Sprintf("%.2f", claimed+o.open[aff])
				o.open[aff] = 0
				made = append(made, p)
			}
			answer(200, map[string]any{"period": period, "created": len(made), "payouts": made})
		case rest == "payouts":
			var rows []map[string]string
			for _, p := range o.payouts {
				if p["period"] == r.URL.Query().Get("period") {
					rows = append(rows, p)
				}
			}
			answer(200, rows)
		case len(seg) == 6 && seg[3] == "payouts" && seg[5] == "status":
			allowed := map[string]string{"APPROVED": "PENDING", "REJECTED": "PENDING", "PAID": "APPROVED"}
			from, ok := allowed[field("status")]
			p := o.payouts[seg[4]]
			switch {
			case !ok:
				answer(400, map[string]any{"error": "invalid status transition"})
			case p == nil:
				answer(404, map[string]any{"error": "payout_not_found"})
			case p["status"] != from:
				answer(409, map[string]any{"error": "invalid_status_transition",
					"message": fmt.Sprintf("Cannot transition payout %s from %s to %s", seg[4], p["status"], field("status"))})
			default:
				p["status"] = field("status")
				answer(200, map[string]any{"ok": true})
			}
		case rest == "managers" && m == http.MethodPost:
			if o.managers[field("email")] {
				answer(409, map[string]any{"error": "user_already_exists"})
				return
			}
			o.managers[field("email")] = true
			answer(201, map[string]any{"keycloakUserId": NameUUID(field("email")), "email": field("email"), "pending": true})
		case rest == "managers":
			var rows []map[string]string
			for email := range o.managers {
				rows = append(rows, map[string]string{"keycloakUserId": NameUUID(email), "email": email})
			}
			answer(200, rows)
		case len(seg) == 6 && seg[3] == "managers" && seg[5] == "reset-password":
			answer(204, nil)
		case rest == "fraud-flags":
			answer(200, o.flags)
		case len(seg) == 6 && seg[3] == "fraud-flags" && seg[5] == "dismiss":
			for _, f := range o.flags {
				if NameUUID("aff.fraud:"+stubBrand+":"+f["playerId"]+":"+f["reason"]) == seg[4] {
					f["dismissed"] = "true"
					o.notes[f["affiliateId"]] = append(o.notes[f["affiliateId"]], map[string]string{"type": "FRAUD_DISMISSED", "read": "false"})
				}
			}
			answer(200, map[string]any{"ok": true})
		case rest == "messages":
			o.notes[field("targetAffiliateId")] = append(o.notes[field("targetAffiliateId")], map[string]string{"type": "OPERATOR_MESSAGE", "read": "false"})
			answer(200, map[string]any{"ok": true})
		case rest == "creatives" && m == http.MethodPost:
			answer(201, map[string]any{"creativeId": NewUUID()})
		case rest == "programme":
			answer(200, map[string]any{"payoutCycleDays": "30", "minimumPayoutAmount": "50.00"})
		default:
			answer(200, map[string]any{"ok": true})
		}
	default:
		answer(404, nil)
	}
}

func run(t *testing.T, name string) (recorder.Recording, string, *stub) {
	t.Helper()
	o := newStub()
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{AffiliateURL: srv.URL, KeycloakURL: srv.URL, Brand: stubBrand, InternalSecret: stubSecret,
		StaffToken: staffTok, AdminToken: adminTok, RecordTo: dir, Commit: "abc1234"}
	var def Def
	for _, d := range Scenarios() {
		if d.Scenario() == name {
			def = d
		}
	}
	if def.Run == nil {
		t.Fatalf("no scenario %q", name)
	}
	Scenario(t, cfg, def.Scenario(), def.Requirement, func(s *Session) { def.Run(t, s) })
	b, err := os.ReadFile(filepath.Join(dir, Service, FileName(name)+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	return rec, string(b), o
}

func step(t *testing.T, rec recorder.Recording, name string) recorder.Step {
	t.Helper()
	for _, s := range rec.Steps {
		if s.Name == name {
			return s
		}
	}
	t.Fatalf("no step %q", name)
	return recorder.Step{}
}

var placeholderAuth = regexp.MustCompile(`^Bearer \{[A-Za-z]+Token\}$`)

func TestEveryScenarioRunsAgainstTheStubAndNoRecordingCarriesACredential(t *testing.T) {
	for _, d := range Scenarios() {
		rec, text, o := run(t, d.Scenario())
		if rec.Service != "programme" || rec.Original["commit"] != "abc1234" || rec.Requirement != d.Requirement || len(rec.Steps) == 0 {
			t.Errorf("%s: header %+v", d.Scenario(), rec)
		}
		for _, secret := range []string{stubSecret, "X-Internal-Secret", "eyJ", "access_token"} {
			if strings.Contains(text, secret) {
				t.Errorf("%s: %s is recorded", d.Scenario(), secret)
			}
		}
		for _, sent := range o.secrets {
			if sent != stubSecret {
				t.Errorf("%s: a notification went without the secret", d.Scenario())
			}
		}
		for _, s := range rec.Steps {
			if a, ok := s.Request.Headers["Authorization"]; ok && !placeholderAuth.MatchString(a) {
				t.Errorf("%s: %s carries %q", d.Scenario(), s.Name, a)
			}
			if strings.Contains(s.Request.Path, stubBrand) {
				t.Errorf("%s: %s: the brand is not templated: %s", d.Scenario(), s.Name, s.Request.Path)
			}
		}
		if steps, _ := json.Marshal(rec.Steps); strings.Contains(string(steps), stubBrand) {
			t.Errorf("%s: the brand is not templated", d.Scenario())
		}
	}
}

func TestStepsCarryTheirRoutesWithPlaceholders(t *testing.T) {
	rec, _, _ := run(t, firstDepositCPA)
	for name, want := range map[string]string{
		"create the plan":                   "POST /v1/affiliates/{brand}/commission-plans",
		"create the account: affiliate":     "POST /v1/affiliates/{brand}/accounts",
		"create a tracking link: affiliate": "POST /v1/my/{affiliate}/tracking-links",
		"notify the registration of player": "POST /internal/v1/affiliates/{brand}/notify/registration",
		"notify the same deposit again":     "POST /internal/v1/affiliates/{brand}/notify/deposit",
		"read revenue for the period":       "GET /v1/my/{affiliate}/revenue?period={period}",
		"read the period's breakdown":       "GET /v1/my/{affiliate}/revenue/{period}/breakdown",
	} {
		s := step(t, rec, name)
		if got := s.Request.Method + " " + s.Request.Path; got != want {
			t.Errorf("%s: %s (want %s)", name, got, want)
		}
	}
	acct := step(t, rec, "create the account: affiliate")
	if !strings.Contains(flat(acct.Request.Body), `"email":"affiliate-{run}@example.com"`) || !strings.Contains(flat(acct.Request.Body), `"planId":"{plan}"`) ||
		acct.Capture["affiliate"] != "$.affiliateId" || acct.Request.Headers["Authorization"] != "Bearer {staffToken}" {
		t.Errorf("account: %s %v %v", acct.Request.Body, acct.Capture, acct.Request.Headers)
	}
	if s := step(t, rec, "read revenue for the period"); s.Request.Headers["Authorization"] != "Bearer {affiliateToken}" {
		t.Errorf("revenue: %v", s.Request.Headers)
	}
	dup := step(t, rec, "notify the same deposit again")
	if !strings.Contains(flat(dup.Request.Body), `"transactionId":"{txn2}"`) || len(dup.Request.Headers) != 1 {
		t.Errorf("duplicate deposit: %s %v", dup.Request.Body, dup.Request.Headers)
	}
}

func TestTheRaceIsRecordedAsOneCreatedAndOneRefusedAndTheCodeIsTemplated(t *testing.T) {
	rec, _, _ := run(t, codeUnique)
	first, second := step(t, rec, "create the same code twice at once (1 of 2)"), step(t, rec, "create the same code twice at once (2 of 2)")
	if first.Response.Status != 201 || second.Response.Status != 409 || !strings.Contains(flat(first.Request.Body), `"code":"{raceCode}"`) {
		t.Errorf("race: %d %d %s", first.Response.Status, second.Response.Status, first.Request.Body)
	}
	if s := step(t, rec, "create the same code in lower case"); !strings.Contains(flat(s.Request.Body), `"code":"{codeLower}"`) {
		t.Errorf("lower case: %s", s.Request.Body)
	}
}

func TestThePayoutIsNamedAsTheOriginalNamesItAndBrandWideListsVary(t *testing.T) {
	rec, _, _ := run(t, closeClaims)
	if s := step(t, rec, "pay"); s.Request.Path != "/v1/affiliates/{brand}/payouts/{payout}/status" {
		t.Errorf("pay: %s", s.Request.Path)
	}
	if s := step(t, rec, "close the next period"); s.Request.Path != "/v1/affiliates/{brand}/payouts/close?period={nextPeriod}" || s.Vary["$.payouts"] == "" {
		t.Errorf("close: %s %v", s.Request.Path, s.Vary)
	}
	if s := step(t, rec, "pay the rejected payout"); s.Response.Status != 409 || !strings.Contains(flat(s.Response.Body), "REJECTED") {
		t.Errorf("rejected: %d %s", s.Response.Status, s.Response.Body)
	}
	if NameUUID("aff.payout:a:2026-11") != NameUUID("aff.payout:a:2026-11") || NameUUID("x") == NameUUID("y") ||
		!regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(NameUUID("aff.payout:a:2026-11")) {
		t.Errorf("NameUUID is not a version 3 UUID")
	}
}

func TestSetupStepsAreMarked(t *testing.T) {
	rec, _, _ := run(t, ngrFold)
	if s := step(t, rec, "setup: the brand's settings at the original's defaults, fees at zero"); s.Request.Path != "/v1/affiliates/{brand}/settings" {
		t.Errorf("settings: %s", s.Request.Path)
	}
	if s := step(t, rec, "notify last period's GGR: carrier"); !strings.Contains(flat(s.Request.Body), `"period":"{previousPeriod}"`) {
		t.Errorf("ggr: %s", s.Request.Body)
	}
}

func TestNothingLeavesTheMachine(t *testing.T) {
	if _, err := PasswordToken(http.DefaultClient, "https://keycloak.example.com", "eitheror", "affiliate-portal", "u", "p"); err == nil {
		t.Fatal("a sign-in off the machine was attempted")
	}
}

// featureScenarios is each feature's scenarios, an outline's once per example by its first column.
func featureScenarios(t *testing.T) map[string][]string {
	files, err := filepath.Glob(filepath.Join("..", "..", "..", "features", "programme", "*.feature"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no features: %v", err)
	}
	out := map[string][]string{}
	for _, path := range files {
		f, err := os.Open(path)
		if err != nil {
			t.Fatal(err)
		}
		name := filepath.Base(path)
		outline, header := "", false
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			line := strings.TrimSpace(sc.Text())
			switch {
			case strings.HasPrefix(line, "Scenario Outline:"):
				outline, header = strings.TrimSpace(strings.TrimPrefix(line, "Scenario Outline:")), true
			case strings.HasPrefix(line, "Scenario:"):
				outline = ""
				out[name] = append(out[name], strings.TrimSpace(strings.TrimPrefix(line, "Scenario:")))
			case outline != "" && strings.HasPrefix(line, "|"):
				if header {
					header = false
					continue
				}
				out[name] = append(out[name], outline+" ["+strings.TrimSpace(strings.Split(line, "|")[1])+"]")
			}
		}
		_ = f.Close()
	}
	return out
}

func TestEveryScenarioIsNamedAsItsFeatureNamesItAndEveryFeatureScenarioIsDrivenOrSaidWhyNot(t *testing.T) {
	driven := map[string]string{} // feature scenario → the recording that shows it
	for _, d := range Scenarios() {
		if !strings.HasPrefix(d.Requirement, "013/FR-") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
		for _, n := range append([]string{d.Name}, d.Covers...) {
			if other, ok := driven[n]; ok {
				t.Errorf("%q is shown by %q and %q", n, other, d.Scenario())
			}
			driven[n] = d.Scenario()
		}
	}
	known := map[string]bool{}
	features := featureScenarios(t)
	for file, names := range features {
		for _, n := range names {
			known[n] = true
			_, isDriven := driven[n]
			whole := NotDrivenFeatures[file] != ""
			if !isDriven && NotDriven[n] == "" && !whole {
				t.Errorf("%s: %q is neither driven nor said why not", file, n)
			}
			if isDriven && (NotDriven[n] != "" || whole) {
				t.Errorf("%s: %q is driven and said not to be", file, n)
			}
		}
	}
	for n := range driven {
		if !known[n] {
			t.Errorf("no feature names %q", n)
		}
	}
	for n := range NotDriven {
		if !known[n] {
			t.Errorf("NotDriven names %q, which no feature does", n)
		}
	}
	for file := range NotDrivenFeatures {
		if _, ok := features[file]; !ok {
			t.Errorf("NotDrivenFeatures names %s, which is not a feature", file)
		}
	}
}

func TestTheDriverScenariosAreThoseOfTheRecordingContract(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("..", "..", "..", "specs", "013-affiliate-programme", "contracts", "recording.md"))
	if err != nil {
		t.Fatal(err)
	}
	row := regexp.MustCompile("(?m)^\\| `([^`]+)` \\|")
	var contract, ours []string
	for _, m := range row.FindAllStringSubmatch(string(b), -1) {
		contract = append(contract, m[1])
	}
	for _, d := range Scenarios() {
		ours = append(ours, d.Driver)
	}
	if strings.Join(contract, "\n") != strings.Join(ours, "\n") {
		t.Errorf("the contract's scenarios:\n%s\nthe driver's:\n%s", strings.Join(contract, "\n"), strings.Join(ours, "\n"))
	}
	files := map[string]bool{}
	for _, d := range Scenarios() {
		if files[FileName(d.Scenario())] {
			t.Errorf("two recordings in %s", FileName(d.Scenario()))
		}
		files[FileName(d.Scenario())] = true
	}
}

// flat is a recorded body without the recording's indentation.
func flat(b json.RawMessage) string {
	var v any
	if json.Unmarshal(b, &v) != nil {
		return string(b)
	}
	out, _ := json.Marshal(v)
	return strings.ReplaceAll(string(out), `\u0026`, "&")
}
