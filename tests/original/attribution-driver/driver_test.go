package attributiondriver

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

// stub answers as the original's affiliate service and substrate do, as far as the recordings need:
// twins kept by namespace and id, the click looking its tag up in `affiliate-links`, a registration
// writing its twin once, a deposit counting a first deposit in the revenue twin, a bonus cost without
// a transaction id refused.
type stub struct {
	mu      sync.Mutex
	twins   map[string]map[string]string
	secrets []string
}

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	raw, _ := io.ReadAll(r.Body)
	body := map[string]string{}
	_ = json.Unmarshal(raw, &body)
	answer := func(status int, v any) {
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
	path := r.URL.Path
	switch {
	case strings.HasPrefix(path, "/twins/") && r.Method == http.MethodPost:
		o.twins[strings.TrimPrefix(path, "/twins/")] = body
		answer(201, map[string]any{"ok": true})
	case strings.HasPrefix(path, "/twins/"):
		if t, ok := o.twins[strings.TrimPrefix(path, "/twins/")]; ok {
			answer(200, t)
		} else {
			answer(404, map[string]any{"error": "not found"})
		}
	case path == "/v1/track/click":
		link, ok := o.twins["affiliate-links/"+body["btag"]]
		switch {
		case body["btag"] == "":
			answer(400, map[string]any{"error": "btag is required"})
		case ok && link["affiliateId"] != "":
			answer(200, map[string]any{"ok": true, "clickId": NewUUID()})
		default:
			answer(200, map[string]any{"ok": true})
		}
	case strings.HasPrefix(path, "/internal/v1/affiliates/"):
		o.secrets = append(o.secrets, r.Header.Get("X-Internal-Secret"))
		parts := strings.Split(path, "/") // "", internal, v1, affiliates, brand, notify, kind
		brand, kind := parts[4], parts[6]
		switch kind {
		case "registration":
			reg := "affiliate-registration-" + brand + "/" + body["playerId"]
			if _, ok := o.twins[reg]; ok {
				answer(200, map[string]any{"ok": true, "duplicate": true})
				return
			}
			link := o.twins["affiliate-links/"+body["btag"]]
			o.twins[reg] = map[string]string{"affiliateId": link["affiliateId"], "btag": body["btag"]}
			answer(200, map[string]any{"ok": true, "affiliateId": link["affiliateId"]})
		case "deposit":
			reg := o.twins["affiliate-registration-"+brand+"/"+body["playerId"]]
			aff := reg["affiliateId"]
			for k, t := range o.twins {
				if strings.HasPrefix(k, "affiliate-revenue-"+aff+"-"+brand+"/") {
					t["ftdCount"] = "1"
					answer(200, map[string]any{"ok": true, "affiliateId": aff})
					return
				}
			}
			o.twins[fmt.Sprintf("affiliate-revenue-%s-%s/%s", aff, brand, revenueID(aff, brand))] = map[string]string{"ftdCount": "1", "cpaCommission": "50.00"}
			answer(200, map[string]any{"ok": true, "affiliateId": aff})
		case "bonus-cost":
			if body["transactionId"] == "" {
				answer(400, map[string]any{"error": "playerId, transactionId and amount are required"})
				return
			}
			answer(200, map[string]any{"ok": true})
		default:
			answer(200, map[string]any{"ok": true})
		}
	default:
		w.WriteHeader(404)
	}
}

func revenueID(aff, brand string) string {
	return NameUUID("aff.rev:" + aff + ":" + brand + ":" + time.Now().UTC().Format("2006-01"))
}

func record(t *testing.T, name string) (recorder.Recording, string, *stub) {
	t.Helper()
	o := &stub{twins: map[string]map[string]string{}}
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	cfg := Config{AffiliateURL: srv.URL, SubstrateURL: srv.URL, InternalSecret: "s3cret", RecordTo: dir, Commit: "abc1234"}
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

func TestEveryScenarioRunsAgainstTheStubAndNoRecordingCarriesTheSecret(t *testing.T) {
	for _, d := range Scenarios() {
		rec, text, o := record(t, d.Scenario())
		if rec.Service != "attribution" || rec.Original["commit"] != "abc1234" || rec.Requirement != d.Requirement {
			t.Errorf("%s: header %+v", d.Scenario(), rec)
		}
		if strings.Contains(text, "s3cret") || strings.Contains(text, "X-Internal-Secret") {
			t.Errorf("%s: the secret or its header is recorded", d.Scenario())
		}
		for _, sent := range o.secrets {
			if sent != "s3cret" {
				t.Errorf("%s: a notification went without the secret", d.Scenario())
			}
		}
		if strings.Contains(text, "brand-") {
			t.Errorf("%s: the run's brand is not templated", d.Scenario())
		}
	}
}

func TestTheSeedIsSetupStepsUnderTheOriginalsNamespacesWithTheTagAsALegacyUUID(t *testing.T) {
	rec, _, _ := record(t, "a visitor who registers within the attribution window is bound once and for good")
	for name, path := range map[string]string{
		"setup: the commission plan":       "/twins/affiliate-plans-{brand}/{plan}",
		"setup: the affiliate":             "/twins/affiliates-{brand}/{affiliate}",
		"setup: the link in the tag index": "/twins/affiliate-links/{btag}",
		"setup: the link under the brand":  "/twins/affiliate-links-{brand}/{btag}",
	} {
		if s := step(t, rec, name); s.Request.Path != path || s.Request.Service != "substrate" || s.Response.Status != 201 {
			t.Errorf("%s: %s %s %d", name, s.Request.Service, s.Request.Path, s.Response.Status)
		}
	}
	reg := step(t, rec, "notify the registration")
	if reg.Request.Path != "/internal/v1/affiliates/{brand}/notify/registration" ||
		!strings.Contains(flat(reg.Request.Body), `"btag":"{btag}"`) || !strings.Contains(flat(reg.Request.Body), `"clickId":"{originalClickId}"`) {
		t.Errorf("registration: %s %s", reg.Request.Path, reg.Request.Body)
	}
	if s := step(t, rec, "observe: the player's registration"); s.Request.Path != "/twins/affiliate-registration-{brand}/{player}" {
		t.Errorf("observe: %s", s.Request.Path)
	}
}

func TestTheClickVariesOnTheOriginalsClickIdAndCarriesTheTagAndTheCampaign(t *testing.T) {
	rec, _, _ := record(t, landing)
	s := step(t, rec, "land with the tag")
	if s.Vary["$.clickId"] == "" || s.Capture["originalClickId"] != "$.clickId" {
		t.Errorf("vary %v capture %v", s.Vary, s.Capture)
	}
	for _, want := range []string{`"btag":"{btag}"`, `"clickId":"{clickId}"`, `"deviceType":"mobile"`, `"utmCampaign":"autumn"`} {
		if !strings.Contains(flat(s.Request.Body), want) {
			t.Errorf("body lacks %s: %s", want, s.Request.Body)
		}
	}
	none, _, _ := record(t, EdgeNoTag)
	if s := step(t, none, "land with no tag"); s.Response.Status != 400 || strings.Contains(flat(s.Request.Body), "btag") {
		t.Errorf("no tag: %d %s", s.Response.Status, s.Request.Body)
	}
}

func TestTheBonusCostIsSentAsCasinoServiceSendsItAndRefused(t *testing.T) {
	rec, _, _ := record(t, money+" [restricted funds are awarded]")
	s := step(t, rec, "notify the bonus cost as casino-service sends it")
	if s.Response.Status != 400 || !strings.Contains(flat(s.Request.Body), `"claimId":"{claim}"`) {
		t.Errorf("bonus cost: %d %s", s.Response.Status, s.Request.Body)
	}
}

func TestTheFirstDepositIsReadFromThisMonthsRevenueTwin(t *testing.T) {
	rec, _, _ := record(t, "a player's first captured deposit emits a first-deposit fact and a deposit fact, the first once in the player's life")
	s := step(t, rec, "observe: the affiliate's revenue this month")
	if s.Request.Path != "/twins/affiliate-revenue-{affiliate}-{brand}/{revenue}" || !strings.Contains(flat(s.Response.Body), `"ftdCount":"1"`) {
		t.Errorf("revenue: %s %s", s.Request.Path, s.Response.Body)
	}
}

// featureScenarios is each feature's scenarios, an outline's once per example by its first column.
func featureScenarios(t *testing.T) map[string][]string {
	files, err := filepath.Glob(filepath.Join("..", "..", "..", "features", "attribution", "*.feature"))
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
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Scenario()] {
			t.Errorf("two scenarios named %q", d.Scenario())
		}
		driven[d.Scenario()] = true
		if !strings.HasPrefix(d.Requirement, "012/FR-") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
	}
	known := map[string]bool{EdgeNoTag: true}
	features := featureScenarios(t)
	for file, names := range features {
		for _, n := range names {
			known[n] = true
			whole := NotDrivenFeatures[file] != ""
			if !driven[n] && NotDriven[n] == "" && !whole {
				t.Errorf("%s: %q is neither driven nor said why not", file, n)
			}
			if driven[n] && (NotDriven[n] != "" || whole) {
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

// flat is a recorded body without the recording's indentation.
func flat(b json.RawMessage) string {
	var out bytes.Buffer
	if err := json.Compact(&out, b); err != nil {
		return string(b)
	}
	return out.String()
}
