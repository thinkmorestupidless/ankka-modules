package rewardsdriver

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

const (
	signing  = "s3cret"
	operator = "operator-token-0000"
	unleash  = "unleash-admin-token-0000"
)

// stub is an original that registers, verifies and signs in, keeps twins, answers the reward routes,
// holds fake-st8's bonuses and Unleash's admin API, and refuses a webhook whose signature does not
// verify under WebhookKey, as the original does.
type stub struct {
	mu        sync.Mutex
	n         int
	player    string
	webhooks  []bool              // whether each webhook's signature verified
	unleashed []string            // every Unleash call, as "<method> <path> <Authorization>"
	bodies    map[string][]string // path -> every body posted there
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	raw, _ := io.ReadAll(r.Body)
	o.bodies[r.URL.Path] = append(o.bodies[r.URL.Path], string(raw))
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	p := r.URL.Path
	reply := func(status int, format string, args ...any) {
		w.WriteHeader(status)
		fmt.Fprintf(w, format, args...)
	}
	switch {
	case strings.HasPrefix(p, "/api/admin/"):
		o.unleashed = append(o.unleashed, r.Method+" "+p+" "+r.Header.Get("Authorization"))
		switch {
		case r.Method == http.MethodGet:
			reply(404, `{"message":"not found"}`)
		case strings.HasSuffix(p, "/strategies"):
			reply(200, `{"id":"strategy-1"}`)
		default:
			reply(200, `{}`)
		}
	case strings.HasSuffix(p, "/register"):
		o.n++
		o.player = fmt.Sprintf("0000000%d-aaaa-4aaa-8aaa-00000000000%d", o.n, o.n)
		reply(201, `{"playerId":%q,"status":"pending_email_verification"}`, o.player)
	case strings.HasSuffix(p, "/verify-email"):
		reply(200, `{"status":"verified"}`)
	case strings.HasSuffix(p, "/login"):
		o.n++
		reply(200, `{"token":"jwt-%d-secretpart","refreshToken":"rt-%d-secretpart"}`, o.n, o.n)
	case strings.HasPrefix(p, "/v1/casino/customerio/webhook/"):
		ts := r.Header.Get("X-CIO-Timestamp")
		ok := ts != "" && r.Header.Get("X-CIO-Signature") == CIOSignature(WebhookKey, ts, raw)
		o.webhooks = append(o.webhooks, ok)
		if !ok {
			reply(401, `{"error":"invalid_signature"}`)
			return
		}
		reply(503, `{"error":"ELIGIBILITY_CHECK_UNAVAILABLE"}`)
	case strings.HasPrefix(p, "/twins/casino-spinvibe/") && r.Method == http.MethodGet:
		reply(200, `{"pending_duplicate_check":"false"}`)
	case strings.HasPrefix(p, "/twins/free-spins-grants/") && r.Method == http.MethodGet:
		reply(200, `{"properties":{"claim_ref":"bonus-claims/claim-1"}}`)
	case strings.HasPrefix(p, "/twins/bonus-claims/") && r.Method == http.MethodGet:
		reply(200, `{"status":"ACTIVE"}`)
	case strings.HasPrefix(p, "/twins/casino/") && r.Method == http.MethodGet:
		reply(200, `{"crm_config":"{\"webhookSigningKey\":\"the-brands-own\"}"}`)
	case strings.HasPrefix(p, "/twins/"):
		reply(201, `{}`)
	case strings.HasPrefix(p, "/test/bonus/"):
		reply(200, `{"instances":{%q:{"status":"canceled","remaining":10}}}`, o.player)
	case strings.Contains(p, "/free-spins/spin-values/"):
		reply(502, `{"error":"provider_unavailable"}`)
	case strings.Contains(p, "/bonus/grant/"):
		if body["type"] == "FREE_SPINS" {
			reply(200, `{"status":"granted","grantId":"grant-1","type":"FREE_SPINS","providerStatus":"active"}`)
			return
		}
		reply(200, `{"status":"granted","bonusAmount":"20.00","wageringRequired":"100.00"}`)
	case strings.HasSuffix(p, "/bonuses") && strings.Contains(p, "/players/"):
		reply(200, `[{"grantRef":"bonus-claims/claim-1","status":"ACTIVE","faceValue":"20.00","grantedAt":"2026-11-02T00:00:00Z"}]`)
	case strings.HasSuffix(p, "/bonuses"):
		reply(200, `{"items":[],"total":1}`)
	case strings.HasSuffix(p, "/revoke"):
		reply(200, `{"status":"revoked"}`)
	case strings.HasSuffix(p, "/cancel"):
		reply(404, `{"error":"grant_not_found"}`)
	case strings.HasSuffix(p, "/reorder"):
		reply(200, `{"updated":2}`)
	case strings.Contains(p, "/bonus/offers/") && r.Method == http.MethodDelete:
		reply(200, `{"status":"deactivated"}`)
	case strings.Contains(p, "/bonus/offers/"):
		reply(200, `{"isActive":false,"priorityOrder":1}`)
	case strings.Contains(p, "/promo-code/"):
		reply(200, `{"valid":true}`)
	case strings.HasSuffix(p, "/state"):
		reply(200, `{"state":"none"}`)
	default:
		reply(200, `{}`)
	}
}

// lenient keeps the scenario's expectations as notes: the stub is not the original, and a run
// records what it answers without failing.
type lenient struct {
	*testing.T
	notes []string
}

func (l *lenient) Errorf(format string, args ...any) {
	l.notes = append(l.notes, fmt.Sprintf(format, args...))
}

func stubConfig(srv *httptest.Server, dir, unleashToken string) Config {
	return Config{CasinoURL: srv.URL, KeycloakURL: srv.URL, SubstrateURL: srv.URL, FakeSt8URL: srv.URL, UnleashURL: srv.URL,
		UnleashToken: unleashToken, Brand: "spinvibe", SigningSecret: signing, OperatorToken: operator,
		RecordTo: dir, Commit: "abc1234", Patience: 50 * time.Millisecond}
}

func record(t *testing.T, def Def) (recorder.Recording, string, *stub) {
	t.Helper()
	o := &stub{bodies: map[string][]string{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	run := &lenient{T: t}
	Scenario(run, stubConfig(srv, dir, unleash), def.Scenario(), def.Requirement, func(s *Session) { def.Run(run, s) })
	b, err := os.ReadFile(filepath.Join(dir, "rewards", FileName(def.Scenario())+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	return rec, string(b), o
}

func find(t *testing.T, name string) Def {
	t.Helper()
	for _, d := range Scenarios() {
		if d.Scenario() == name {
			return d
		}
	}
	t.Fatalf("no scenario %q", name)
	return Def{}
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

// compact is a recorded body on one line: the recording indents it.
func compact(b json.RawMessage) string {
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		return string(b)
	}
	return buf.String()
}

func TestTheOriginalsNameUUIDsMajorUnitsAndWebhookSignature(t *testing.T) {
	// Computed outside Go: MD5 of the name with the version 3 and IETF variant bits, as Java's
	// UUID.nameUUIDFromBytes makes it.
	if got := NameUUID("seed.sub-brand.spinvibe"); got != "2867b301-c32d-3879-b766-10321f0302a8" {
		t.Errorf("got %s", got)
	}
	for minor, want := range map[int64]string{0: "0.00", -100: "-1.00", 2000: "20.00", 1050: "10.50", 7: "0.07"} {
		if got := Decimal(minor); got != want {
			t.Errorf("Decimal(%d) = %s, want %s", minor, got, want)
		}
	}
	// `printf 'v0:1700000000:{}' | openssl dgst -sha256 -hmac key`
	if got := CIOSignature("key", "1700000000", []byte("{}")); got != "fd9fa6c53274153a1b20c4777f83ea49e617d4b58da089ca0e8ae76300d03970" {
		t.Errorf("signature %s", got)
	}
}

// Every scenario runs against the stub, and none writes the signing secret, Unleash's token or the
// webhook's signature anywhere, nor sends a token or an address as it is. A sign-in's answer keeps its
// token, as every driver's does; the merge redacts it.
func TestEveryScenarioRecordsNoCredentialAndNamesEveryTokenItCarries(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			rec, text, _ := record(t, d)
			if rec.Service != "rewards" || rec.Requirement != d.Requirement || rec.Original["commit"] != "abc1234" || len(rec.Steps) == 0 {
				t.Fatalf("header: %+v", rec)
			}
			for _, raw := range []string{signing, unleash, "X-CIO-Signature", "X-CIO-Timestamp"} {
				if strings.Contains(text, raw) {
					t.Errorf("the recording carries %q", raw)
				}
			}
			for _, s := range rec.Steps {
				sent := s.Request.Path + string(s.Request.Body) + fmt.Sprint(s.Request.Headers)
				for _, raw := range []string{operator, "secretpart", "@example.test"} {
					if strings.Contains(sent, raw) {
						t.Errorf("step %q sends %q: %s", s.Name, raw, sent)
					}
				}
				if a, ok := s.Request.Headers["Authorization"]; ok && !(strings.HasPrefix(a, "Bearer {") && strings.HasSuffix(a, "}")) {
					t.Errorf("step %q: Authorization %q", s.Name, a)
				}
			}
		})
	}
}

func TestTheWebhookIsSignedWithTheBrandsKeyWhichIsRestoredAndTheSignatureNeverRecorded(t *testing.T) {
	rec, _, o := record(t, find(t, "an award by the brand's engagement during the duplicate check is answered to try again later"))
	if len(o.webhooks) != 1 || !o.webhooks[0] {
		t.Fatalf("the webhook's signatures verified: %v", o.webhooks)
	}
	hook := step(t, rec, "the brand's engagement awards the offer")
	if hook.Request.Path != "/v1/casino/customerio/webhook/spinvibe" || len(hook.Request.Headers) != 1 ||
		compact(hook.Request.Body) != `{"action":"grant_bonus","offer_id":"{offerId}","player_id":"{playerId}"}` {
		t.Errorf("webhook: %+v %s", hook.Request, hook.Request.Body)
	}
	twin := "/twins/casino/" + NameUUID("merchant.crm-config/spinvibe")
	if back := step(t, rec, "setup: restore the brand's legacy CRM config"); back.Request.Path != twin ||
		!strings.Contains(compact(back.Request.Body), "the-brands-own") {
		t.Errorf("restore: %s %s", back.Request.Path, back.Request.Body)
	}
}

func TestAGateWritesEveryFlagAndItsRegistrationTimeByName(t *testing.T) {
	rec, _, _ := record(t, find(t, "a no-deposit offer claimed while the player's duplicate check is in progress is refused for now"))
	flags := step(t, rec, "setup: the player registered five minutes ago and their duplicate check is in progress")
	if flags.Request.Path != "/twins/casino-spinvibe/{playerId}" || compact(flags.Request.Body) !=
		`{"no_deposit_offer_blocked":"false","pending_duplicate_check":"true","registered_at":"{registeredAt}","suspected_duplicate":"false"}` {
		t.Errorf("flags: %+v %s", flags.Request, flags.Request.Body)
	}
	offer := step(t, rec, "setup: the offer offerId")
	if offer.Request.Path != "/twins/casino-bonus-offers/{offerId}" || !strings.Contains(compact(offer.Request.Body), `"redemption_codes":"{code}:0"`) ||
		!strings.Contains(compact(offer.Request.Body), `"no_deposit_required":"true"`) {
		t.Errorf("offer: %+v %s", offer.Request, offer.Request.Body)
	}
	claim := step(t, rec, "claim the offer by its code")
	if claim.Request.Path != "/v1/casino/bonus/spinvibe/enter-code" || compact(claim.Request.Body) != `{"code":"{code}"}` ||
		claim.Request.Headers["Authorization"] != "Bearer {token}" {
		t.Errorf("claim: %+v %s", claim.Request, claim.Request.Body)
	}
	if last := rec.Steps[len(rec.Steps)-1]; last.Name != "setup: deactivate the offer offerId" || compact(last.Request.Body) != `{"is_active":"false"}` {
		t.Errorf("the last step is %q %s", last.Name, last.Request.Body)
	}
}

func TestTheKillSwitchIsSetForTheBrandAloneWithATokenNeverRecordedAndPutBackAfterwards(t *testing.T) {
	rec, _, o := record(t, find(t, "the duplicate rule is skipped on a brand that turned duplicate detection off"))
	want := []string{
		"GET /api/admin/projects/default/features/" + KillSwitch,
		"POST /api/admin/projects/default/features",
		"POST /api/admin/projects/default/features/" + KillSwitch + "/environments/development/strategies",
		"POST /api/admin/projects/default/features/" + KillSwitch + "/environments/development/on",
		"POST /api/admin/projects/default/features/" + KillSwitch + "/environments/development/off",
		"DELETE /api/admin/projects/default/features/" + KillSwitch + "/environments/development/strategies/strategy-1",
	}
	if len(o.unleashed) != len(want) {
		t.Fatalf("Unleash received %v", o.unleashed)
	}
	for i, w := range want {
		if o.unleashed[i] != w+" "+unleash {
			t.Errorf("call %d: %q, want %q with the token", i, o.unleashed[i], w)
		}
	}
	strategy := step(t, rec, "setup: turn detection off for the brand")
	if b := compact(strategy.Request.Body); !strings.Contains(b, `"contextName":"brandId","operator":"IN","values":["spinvibe"]`) ||
		len(strategy.Request.Headers) != 1 {
		t.Errorf("strategy: %+v %s", strategy.Request, b)
	}
}

func TestWithNoUnleashTokenTheKillSwitchsScenarioIsSkippedAndWritesNothing(t *testing.T) {
	o := &stub{bodies: map[string][]string{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	def := find(t, "the duplicate rule is skipped on a brand that turned duplicate detection off")
	var skipped bool
	t.Run("without a token", func(t *testing.T) {
		defer func() { skipped = t.Skipped() }()
		Scenario(t, stubConfig(srv, dir, ""), def.Scenario(), def.Requirement, func(s *Session) { def.Run(t, s) })
	})
	if !skipped {
		t.Error("the scenario was not skipped")
	}
	if entries, _ := os.ReadDir(filepath.Join(dir, "rewards")); len(entries) != 0 || len(o.bodies) != 0 {
		t.Errorf("a skipped scenario wrote %d recordings and made %d calls", len(entries), len(o.bodies))
	}
}

func TestRevokingFreeRoundsNamesTheRewardTheGrantPointsAt(t *testing.T) {
	rec, _, _ := record(t, find(t, "an operator revoking free rounds not yet played cancels them at the game provider"))
	give := step(t, rec, "setup: give the player free rounds")
	if b := compact(give.Request.Body); give.Request.Path != "/v1/casino/bonus/grant/spinvibe/{playerId}" ||
		!strings.Contains(b, `"type":"FREE_SPINS"`) || !strings.Contains(b, `"gameId":"book-of-ra"`) || !strings.Contains(b, `"spinValue":"0.10"`) ||
		give.Request.Headers["Authorization"] != "Bearer {operatorToken}" {
		t.Errorf("give: %+v %s", give.Request, b)
	}
	rv := step(t, rec, "revoke the free rounds as forfeit")
	if rv.Request.Method != http.MethodPatch || rv.Request.Path != "/v1/casino/spinvibe/bonuses/{claimId}/revoke" ||
		compact(rv.Request.Body) != `{"mode":"forfeit"}` {
		t.Errorf("revoke: %+v %s", rv.Request, rv.Request.Body)
	}
	if st8 := step(t, rec, "observe: the game provider has cancelled them"); st8.Request.Service != "fake-st8" || st8.Request.Path != "/test/bonus/{grantId}" {
		t.Errorf("fake-st8: %+v", st8.Request)
	}
}

func TestAFreeBonusIsSentInMajorUnitsAndItsRewardReadFromTheOperatorsList(t *testing.T) {
	rec, text, _ := record(t, find(t, "an operator giving a free bonus with no offer awards restricted funds with the amount and wagering they set"))
	give := step(t, rec, "give the player a free bonus")
	if compact(give.Request.Body) != `{"bonusAmount":"20.00","currency":"EUR","wager":"5"}` {
		t.Errorf("give: %s", give.Request.Body)
	}
	if !strings.Contains(text, `"claimId": "$[0].grantRef"`) {
		t.Error("the reward's id is not captured from the operator's list")
	}
	if tw := step(t, rec, "observe: the reward is active with its wagering window"); tw.Request.Path != "/twins/bonus-claims/{claimId}" {
		t.Errorf("twin: %+v", tw.Request)
	}
}

// featureScenarios is every scenario of features/rewards/*.feature, an outline's once per example by
// its first column.
func featureScenarios(t *testing.T) []string {
	files, err := filepath.Glob(filepath.Join("..", "..", "..", "features", "rewards", "*.feature"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no features: %v", err)
	}
	var out []string
	for _, file := range files {
		f, err := os.Open(file)
		if err != nil {
			t.Fatal(err)
		}
		outline, header := "", false
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			line := strings.TrimSpace(sc.Text())
			switch {
			case strings.HasPrefix(line, "Scenario Outline:"):
				outline, header = strings.TrimSpace(strings.TrimPrefix(line, "Scenario Outline:")), true
			case strings.HasPrefix(line, "Scenario:"):
				outline = ""
				out = append(out, strings.TrimSpace(strings.TrimPrefix(line, "Scenario:")))
			case outline != "" && strings.HasPrefix(line, "|"):
				if header {
					header = false
					continue
				}
				out = append(out, outline+" ["+strings.TrimSpace(strings.Split(line, "|")[1])+"]")
			}
		}
		_ = f.Close()
	}
	return out
}

func TestEveryScenarioIsNamedAsItsFeatureNamesItAndEveryFeatureScenarioIsDrivenOrSaidWhyNot(t *testing.T) {
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Scenario()] {
			t.Errorf("two scenarios named %q", d.Scenario())
		}
		driven[d.Scenario()] = true
		if !strings.HasPrefix(d.Requirement, "011/FR-") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
	}
	known := map[string]bool{}
	for _, n := range featureScenarios(t) {
		known[n] = true
		if !driven[n] && NotDriven[n] == "" {
			t.Errorf("%q is neither driven nor said why not", n)
		}
		if driven[n] && NotDriven[n] != "" {
			t.Errorf("%q is driven and said not to be", n)
		}
	}
	for n := range driven {
		if !known[n] {
			t.Errorf("no feature names a scenario %q", n)
		}
	}
	for n := range NotDriven {
		if !known[n] {
			t.Errorf("NotDriven names %q, which no feature does", n)
		}
	}
}
