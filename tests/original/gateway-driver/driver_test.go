package gatewaydriver

import (
	"bufio"
	"bytes"
	"crypto/ed25519"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
	"github.com/eitheror-io/ankka-casino/tests/stubs/bouncemoney"
	"github.com/eitheror-io/ankka-casino/tests/stubs/digitarium"
	"github.com/eitheror-io/ankka-casino/tests/stubs/dreamzpay"
	"github.com/eitheror-io/ankka-casino/tests/stubs/eupaymentz"
	"github.com/eitheror-io/ankka-casino/tests/stubs/t365"
	"github.com/eitheror-io/ankka-casino/tests/stubs/unityfinance"
)

// fakes are a gateway and a psp-stub standing in for the original: the gateway answers an initiation
// with a reference; the stub logs the gateway's provider call (with a credential and a token in it)
// and, on /__webhook, delivers the capture to the initiation's callback as the original does, with
// its internal secret.
type fakes struct {
	gateway, stub *httptest.Server
	mu            sync.Mutex
	callback      string
	received      []map[string]any
}

const fakeSecret = "s3cret-internal"

func newFakes(t *testing.T) *fakes {
	f := &fakes{}
	f.gateway = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		switch {
		case r.URL.Path == "/v1/psp/hpp/initiate":
			f.mu.Lock()
			f.callback, _ = body["callbackUrl"].(string)
			f.received = append(f.received, map[string]any{
				"at": time.Now().UTC().Format(time.RFC3339Nano), "method": "POST", "path": "/api/v1/external/payment/hosted",
				"headers": map[string]string{"Apikey": "stub-t365-key", "Content-Type": "application/json"},
				"body":    "orderId=ref-7777777777&amount=50.00&hash=ABCDEF&secretKey=t365-stub-secret",
			})
			f.mu.Unlock()
			writeJSON(w, 200, map[string]string{"redirectUrl": "http://psp-stub/hosted/1", "pspTransactionId": "ref-7777777777"})
		case r.URL.Path == "/internal/v1/psp/redrive-undelivered":
			if r.Header.Get("X-Internal-Secret") != fakeSecret {
				writeJSON(w, 401, map[string]string{"error": "UNAUTHORIZED"})
				return
			}
			writeJSON(w, 200, map[string]int{"outstanding": 0})
		default:
			w.WriteHeader(200)
		}
	}))
	f.stub = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/__control":
			writeJSON(w, 200, map[string]string{"outcome": "pending"})
		case "/__received":
			f.mu.Lock()
			defer f.mu.Unlock()
			writeJSON(w, 200, f.received)
		case "/__webhook":
			var body struct{ Reference string }
			_ = json.NewDecoder(r.Body).Decode(&body)
			f.mu.Lock()
			cb := f.callback
			f.mu.Unlock()
			req, _ := http.NewRequest(http.MethodPost, cb, strings.NewReader(`{"pspTransactionId":"`+body.Reference+`"}`))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Internal-Secret", fakeSecret)
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Errorf("the fake gateway's outcome call: %v", err)
				return
			}
			_ = resp.Body.Close()
			writeJSON(w, 200, map[string]any{"status": 200, "body": map[string]string{}})
		}
	}))
	t.Cleanup(func() { f.gateway.Close(); f.stub.Close() })
	return f
}

// compact is a recorded body as one line, as written before the recording was indented.
func compact(b []byte) string {
	var buf bytes.Buffer
	_ = json.Compact(&buf, b)
	return buf.String()
}

func port(t *testing.T, srv *httptest.Server) (host, port string) {
	u, _ := url.Parse(srv.URL)
	h, p, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatal(err)
	}
	return "http://" + h, p
}

func TestARecordingHasTheContractShapeAndNoRunValuesOrSecrets(t *testing.T) {
	f := newFakes(t)
	rc := httptest.NewServer(NewReceiver())
	defer rc.Close()
	host, stubPort := port(t, f.stub)
	p := ProviderNamed("t365")
	p.Port = stubPort
	dir := t.TempDir()
	cfg := Config{GatewayURL: f.gateway.URL, StubHost: host, ReceiverURL: rc.URL, ReceiverLocal: rc.URL,
		InternalSecret: fakeSecret, RecordTo: dir, Commit: "abc1234", Settle: 10 * time.Millisecond, Wait: 2 * time.Second, Attempt: 10 * time.Millisecond}

	var payment string
	name := "a verified webhook reporting success tells the cashier the payment is captured, once, and the provider session records it delivered/t365"
	Scenario(t, cfg, name, "005/FR-014", func(s *Session) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Webhook("success", p, s.Value("reference"), map[string]any{"outcome": "success"}), 200)
		s.Outcomes("the capture", 1)
		s.Want(s.Redrive("the next re-drive runs"), 200)
		payment = s.Value("payment")
	})

	b, err := os.ReadFile(filepath.Join(dir, "psp-gateway", FileName(name)+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	if rec.Scenario != name || rec.Service != "psp-gateway" || rec.Runs != 1 || rec.Original["commit"] != "abc1234" {
		t.Fatalf("header: %+v", rec)
	}
	for _, leaked := range []string{fakeSecret, "t365-stub-secret", "stub-t365-key"} {
		if strings.Contains(string(b), leaked) {
			t.Errorf("a secret reached the recording: %q", leaked)
		}
	}
	names := []string{}
	for _, st := range rec.Steps {
		names = append(names, st.Name)
		text := st.Request.Path + string(st.Request.Body)
		if strings.HasPrefix(st.Name, "observe:") {
			text += string(st.Response.Body)
		}
		for _, run := range []string{payment, "ref-7777777777"} {
			if strings.Contains(text, run) {
				t.Errorf("step %q carries a run's value %q", st.Name, run)
			}
		}
	}
	want := []string{"setup: psp-stub control: pending", "setup: merchant-service holds the terminal", "initiate",
		"observe: the provider call", "webhook: success", "observe: the capture", "time: the next re-drive runs (redrive-undelivered)"}
	if strings.Join(names, "|") != strings.Join(want, "|") {
		t.Fatalf("steps: %v", names)
	}
	initiate, provider, hook, outcome, redrive := rec.Steps[2], rec.Steps[3], rec.Steps[4], rec.Steps[5], rec.Steps[6]
	if initiate.Capture["reference"] != "$.pspTransactionId" || !strings.Contains(compact(initiate.Request.Body), `/v1/payments/{payment}/capture`) {
		t.Errorf("initiate step: %+v", initiate)
	}
	if !strings.Contains(compact(initiate.Request.Body), `"secretKey":"<redacted>"`) {
		t.Errorf("the terminal's secret was not redacted: %s", initiate.Request.Body)
	}
	if !strings.Contains(compact(provider.Response.Body), `"orderId":"{reference}"`) || provider.Vary["$[0].at"] == "" || provider.Vary["$[0].body.hash"] == "" {
		t.Errorf("provider call: %s vary %v", provider.Response.Body, provider.Vary)
	}
	if !strings.Contains(compact(provider.Response.Body), `"Apikey":"<redacted>"`) {
		t.Errorf("the provider call's credential header was not redacted: %s", provider.Response.Body)
	}
	if hook.Request.Service != "psp-stub" || !strings.Contains(compact(hook.Request.Body), `"reference":"{reference}"`) {
		t.Errorf("webhook step: %+v", hook)
	}
	var calls []Call
	if err := json.Unmarshal(outcome.Response.Body, &calls); err != nil || len(calls) != 1 {
		t.Fatalf("outcome step: %s (%v)", outcome.Response.Body, err)
	}
	if calls[0].Path != "/v1/payments/{payment}/capture" || compact(calls[0].Body) != `{"pspTransactionId":"{reference}"}` {
		t.Errorf("outcome call: %+v", calls[0])
	}
	if _, ok := calls[0].Headers["X-Internal-Secret"]; ok {
		t.Error("the gateway's internal secret was recorded")
	}
	if redrive.Response.Status != 200 || redrive.Request.Headers["X-Internal-Secret"] != "" {
		t.Errorf("re-drive step: %+v", redrive)
	}
}

func TestTheReceiverAnswersAsScripted(t *testing.T) {
	rc := NewReceiver()
	srv := httptest.NewServer(rc)
	defer srv.Close()
	post := func(path string) (int, error) {
		c := &http.Client{Timeout: 500 * time.Millisecond}
		resp, err := c.Post(srv.URL+path, "application/json", strings.NewReader(`{"pspTransactionId":"r1"}`))
		if err != nil {
			return 0, err
		}
		_ = resp.Body.Close()
		return resp.StatusCode, nil
	}
	rc.Script("pay-1", unavail, unavail, ok)
	rc.Script("pay-2", badReq)
	rc.Script("pay-3", Answer{SilentMs: 5_000}, ok)
	var got []int
	for i := 0; i < 4; i++ {
		s, _ := post("/v1/payments/pay-1/capture")
		got = append(got, s)
	}
	if fmtInts(got) != "503,503,200,200" {
		t.Errorf("503, 503, then 200 holding: %v", got)
	}
	if s, _ := post("/v1/payments/pay-2/fail"); s != 400 {
		t.Errorf("400: %d", s)
	}
	if s, _ := post("/v1/payments/pay-2/fail"); s != 400 {
		t.Errorf("the last answer holds: %d", s)
	}
	if _, err := post("/v1/payments/pay-3/capture"); err == nil {
		t.Error("a silent answer answered")
	}
	if s, _ := post("/v1/payments/pay-3/capture"); s != 200 {
		t.Errorf("after the silence: %d", s)
	}
	if s, _ := post("/internal/v1/casino/withdrawal-requests/w1/outcome"); s != 200 {
		t.Errorf("unscripted: %d", s)
	}
	calls := rc.Received("pay-3")
	if len(calls) != 2 || calls[0].Answered != 0 || calls[1].Answered != 200 {
		t.Errorf("pay-3's calls: %+v", calls)
	}
	if len(rc.Received()) != 9 {
		t.Errorf("every call: %d", len(rc.Received()))
	}

	rc.Terminal("t-1", map[string]string{"signingKey": "k"})
	resp, _ := http.Get(srv.URL + "/internal/v1/terminals/t-1/properties")
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != 200 || strings.TrimSpace(string(body)) != `{"properties":{"signingKey":"k"}}` {
		t.Errorf("terminal read: %d %s", resp.StatusCode, body)
	}
	if resp, _ := http.Get(srv.URL + "/internal/v1/terminals/t-2/properties"); resp.StatusCode != 404 {
		t.Errorf("an unknown terminal: %d", resp.StatusCode)
	}
	rc.Credentials("sb-1", "unityfinance", map[string]string{"signKey": "k"})
	if resp, _ := http.Get(srv.URL + "/internal/v1/sub-brands/sb-1/psp-credentials/UNITYFINANCE"); resp.StatusCode != 200 {
		t.Errorf("credential read: %d", resp.StatusCode)
	}
	if len(rc.Received()) != 9 {
		t.Error("a read was logged as an outcome call")
	}
}

func fmtInts(xs []int) string {
	var out []string
	for _, x := range xs {
		out = append(out, strconv.Itoa(x))
	}
	return strings.Join(out, ",")
}

func TestTheReceiverKeepsNoSecretHeader(t *testing.T) {
	rc := NewReceiver()
	srv := httptest.NewServer(rc)
	defer srv.Close()
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/v1/payments/p/capture", strings.NewReader(`{}`))
	req.Header.Set("X-Internal-Secret", "x")
	req.Header.Set("Authorization", "Basic eA==")
	req.Header.Set("X-PSP-Signature", "abc")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	h := rc.Received()[0].Headers
	if h["X-Internal-Secret"] != "" || h["Authorization"] != "" || h["X-Psp-Signature"] != "abc" {
		t.Errorf("headers: %v", h)
	}
}

func TestRedactDropsSecretsWhereverTheySit(t *testing.T) {
	in := `{"terminalProperties":{"secureKey":"k1","memberId":"m"},"headers":{"Authorization":"Basic x","Content-Type":"a"},` +
		`"body":"user=u&pass=p2&amount=1.00","list":[{"privateKey":"k3"}]}`
	out := string(Redact(json.RawMessage(in)))
	for _, leaked := range []string{"k1", "Basic x", "p2", "k3"} {
		if strings.Contains(out, leaked) {
			t.Errorf("%q survived: %s", leaked, out)
		}
	}
	for _, kept := range []string{`"memberId":"m"`, `"Content-Type":"a"`, "amount=1.00", "user=u"} {
		if !strings.Contains(out, kept) {
			t.Errorf("%q was lost: %s", kept, out)
		}
	}
}

// Cozeni's request names the coin amount it was quoted as a number: templating it as text left a bare
// `{cryptoAmount}`, which is not JSON, and the recording was written empty (2026-10-20).
func TestATemplatedBodyStaysJSONWhereACapturedValueIsANumber(t *testing.T) {
	s := session(t)
	out := s.templateJSON(json.RawMessage(`{"paymentRef":"0f8fad5b-d9cb-469f-a165-70867728950e","requestAmount":0.00076923,"quoted":"0.00076923"}`))
	if !json.Valid(out) {
		t.Fatalf("not JSON: %s", out)
	}
	for _, want := range []string{`"paymentRef":"{reference}"`, `"requestAmount":0.00076923`, `"quoted":"{cryptoAmount}"`} {
		if !strings.Contains(string(out), want) {
			t.Errorf("%s missing from %s", want, out)
		}
	}
}

// session is a Session for forging only: no recording, the facts a deposit would have noted.
func session(t *testing.T) *Session {
	s := &Session{t: t, cfg: Config{Now: time.Now}, ids: map[string]string{}, seen: map[string]int{}, facts: map[string]string{},
		rec: &recorder.Recording{}}
	s.remember("reference", "0f8fad5b-d9cb-469f-a165-70867728950e")
	s.remember("cryptoAmount", "0.00076923")
	s.facts["txnauthz"] = "c0ffee"
	s.facts["notifyToken"] = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	s.facts["wpbId"] = "42"
	return s
}

func TestEachForgedWebhookIsSignedAsItsProviderSignsAndAnUnsignedOneIsNot(t *testing.T) {
	s := session(t)
	ref := s.Value("reference")
	for _, p := range Providers {
		for _, unsigned := range []bool{false, true} {
			h := p.forge(s, p, Forgery{Unsigned: unsigned})
			if !strings.HasPrefix(h.Path, p.Webhook) {
				t.Errorf("%s: posted to %s", p.Name, h.Path)
			}
			if verifies(t, p, h, ref) == unsigned {
				t.Errorf("%s (unsigned %v): the signature's check said %v: %s %s %v", p.Name, unsigned, !unsigned, h.Path, h.Body.Bytes, h.Headers)
			}
		}
	}
}

// verifies checks a forged webhook as the original's adapter would, with the stub's key.
func verifies(t *testing.T, p Provider, h Hook, ref string) bool {
	form, _ := url.ParseQuery(string(h.Body.Bytes))
	var obj map[string]any
	_ = json.Unmarshal(h.Body.Bytes, &obj)
	str := func(k string) string { v, _ := obj[k].(string); return v }
	switch p.Name {
	case "dreamzpay":
		return form.Get("checksum") == dreamzpay.InboundChecksum(form.Get("paymentId"), ref, form.Get("amount"), form.Get("transactionStatus"), dreamzpayKey)
	case "unityfinance":
		params := map[string]string{}
		for k := range form {
			if k != "ik_sign" {
				params[k] = form.Get(k)
			}
		}
		return form.Get("ik_pm_no") == ref && form.Get("ik_sign") == unityfinance.Sign(params, unityfinanceKey)
	case "t365":
		return str("orderId") == strings.ReplaceAll(ref, "-", "") && str("hash") == t365.Sign(t365Key, str("amount"), str("currency"), str("orderId"), str("status"))
	case "eupaymentz":
		return str("resp_trans_merchant_id") == ref && str("resp_sha") == eupaymentz.Sign(eupaymentzKey, str("resp_trans_id"), str("resp_trans_amount"), str("resp_trans_status"))
	case "bouncemoney":
		sig := h.Headers["X-BounceMoney-Signature"]
		ts, _ := strconv.ParseInt(strings.TrimPrefix(strings.Split(sig, ",")[0], "t="), 10, 64)
		return sig == bouncemoney.Sign(bouncemoneyKey, ts, h.Body.Bytes)
	case "digitarium":
		want, _ := digitarium.Signature(digitariumKey, h.Body.Bytes, "hex")
		return str("referenceId") == ref && h.Headers["Signature"] == want
	case "cozeni":
		return form.Get("paymentRef") == ref && form.Get("txnauthz") == "c0ffee"
	case "morphosis":
		return strings.HasSuffix(h.Path, "/7c9e6679-7425-40de-944b-e07fc1f90ae7") && obj["id"] == "42"
	case "gigadat":
		// Gigadat signs nothing; the forgery differs only in its cookie, which the original never reads.
		return strings.Contains(string(h.Body.Bytes), `"stub-cookie"`) && strings.Contains(h.Path, "transactionId="+ref)
	}
	t.Fatalf("no check for %s", p.Name)
	return false
}

func TestATamperedAmountIsTenTimesTheSessionsInTheProvidersForm(t *testing.T) {
	s := session(t)
	for name, want := range map[string]string{"dreamzpay": `amount=500.00`, "t365": `"amount":"500.00"`, "digitarium": `"amount":500`, "gigadat": `"value":"500"`} {
		p := ProviderNamed(name)
		h := p.forge(s, p, Forgery{Amount: tampered(p)})
		if !strings.Contains(string(h.Body.Bytes), want) {
			t.Errorf("%s: %s lacks %s", name, h.Body.Bytes, want)
		}
	}
}

func TestTheBounceMoneyKeysArePKCS8Ed25519AndDiffer(t *testing.T) {
	keys := map[string]bool{}
	for _, seed := range []byte{1, 2} {
		block, _ := pem.Decode([]byte(bouncemoneyPrivateKey(seed)))
		if block == nil || block.Type != "PRIVATE KEY" {
			t.Fatalf("seed %d: not a PEM private key", seed)
		}
		k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if _, ok := k.(ed25519.PrivateKey); err != nil || !ok {
			t.Fatalf("seed %d: %v %T", seed, err, k)
		}
		keys[string(block.Bytes)] = true
	}
	if len(keys) != 2 || bouncemoneyPrivateKey(1) != bouncemoneyPrivateKey(1) {
		t.Fatal("the keys are not fixed per seed and distinct across seeds")
	}
}

func TestEveryProviderCarriesTheCredentialsItsReadmeNames(t *testing.T) {
	want := map[string][]string{
		"bouncemoney": {"keyId", "privateKey", "signingSecret"}, "cozeni": {"accountId", "apiKey", "merchantId"},
		"dreamzpay": {"memberId", "totype", "secureKey"}, "eupaymentz": {"accountId", "password", "passphrase"},
		"gigadat":   {"campaignId", "accessToken", "securityToken", "siteUrl", "environment"},
		"morphosis": {"user", "pass"}, "t365": {"apiKey", "secretKey", "midId"},
		"unityfinance": {"checkoutId.VISA", "signKey", "userId", "withdrawalApiKey", "accountId", "purseId"},
		"digitarium":   {"apiKey", "signingKey", "environment"},
	}
	ports := map[string]bool{}
	for _, p := range Providers {
		for _, k := range want[p.Name] {
			if p.Properties[k] == "" {
				t.Errorf("%s's terminal lacks %s", p.Name, k)
			}
		}
		if p.Properties[p.Rotate] == "" {
			t.Errorf("%s rotates %q, which it does not carry", p.Name, p.Rotate)
		}
		ports[p.Port] = true
	}
	if len(Providers) != 9 || len(ports) != 9 {
		t.Fatalf("%d providers on %d ports", len(Providers), len(ports))
	}
}

// featureScenarios reads a feature file's scenarios as the driver names them: a scenario by its name,
// an outline once per example by its name and the example's first column in brackets.
func featureScenarios(t *testing.T, file string) []string {
	f, err := os.Open(filepath.Join("..", "..", "..", "features", "payments", file))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var out []string
	outline, examples, header := "", false, false
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		switch {
		case strings.HasPrefix(line, "Scenario Outline:"):
			outline, examples = strings.TrimSpace(strings.TrimPrefix(line, "Scenario Outline:")), false
		case strings.HasPrefix(line, "Scenario:"):
			outline = ""
			out = append(out, strings.TrimSpace(strings.TrimPrefix(line, "Scenario:")))
		case strings.HasPrefix(line, "Examples:"):
			examples, header = outline != "", true
		case examples && strings.HasPrefix(line, "|"):
			if header {
				header = false
				continue
			}
			cell := strings.TrimSpace(strings.Split(strings.Trim(line, "|"), "|")[0])
			out = append(out, outline+" ("+cell+")")
		}
	}
	return out
}

func TestEveryScenarioIsNamedAsItsFeatureNamesItAndEveryFeatureScenarioIsDrivenOrSaidWhyNot(t *testing.T) {
	defs := Scenarios()
	byFeature := map[string]map[string]int{}
	seen := map[string]bool{}
	for _, d := range defs {
		if seen[d.Scenario()] {
			t.Errorf("two scenarios named %q", d.Scenario())
		}
		seen[d.Scenario()] = true
		if byFeature[d.Feature] == nil {
			byFeature[d.Feature] = map[string]int{}
		}
		byFeature[d.Feature][d.Name]++
		if d.Provider != "" && d.Scenario() != d.Name+"/"+d.Provider {
			t.Errorf("%q: the provider is not its suffix", d.Scenario())
		}
		if !strings.HasPrefix(d.Requirement, "005/") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
	}
	for _, file := range []string{providersFeature, cryptoFeature, payoutsFeature, outcomesFeature} {
		names := featureScenarios(t, file)
		known := map[string]bool{}
		for _, n := range names {
			known[n] = true
			if byFeature[file][n] == 0 && NotDriven[n] == "" {
				t.Errorf("%s: %q is neither driven nor said why not", file, n)
			}
		}
		for n := range byFeature[file] {
			if !known[n] {
				t.Errorf("%s names no scenario %q", file, n)
			}
		}
		total := 0
		for _, c := range byFeature[file] {
			total += c
		}
		t.Logf("%s: %d feature scenarios, %d recordings", file, len(names), total)
	}
}

func TestTheDepositScenariosRunForEveryProviderTheFeatureHolds(t *testing.T) {
	count := map[string]int{}
	for _, d := range Scenarios() {
		if d.Feature == providersFeature {
			count[d.Name]++
		}
	}
	for name, want := range map[string]int{
		"a verified webhook reporting success tells the cashier the payment is captured, once, and the provider session records it delivered": 9,
		"a webhook whose signature does not verify is refused as a bad request, logged, and moves nothing":                                    9,
		"a webhook reporting a status that is not final leaves the provider session open and the cashier untold":                              8,
		"a player's browser returning from the payment provider is shown that the payment is being processed and settles nothing":             7,
	} {
		if count[name] != want {
			t.Errorf("%q: %d providers (want %d)", name, count[name], want)
		}
	}
}

func TestRefusesARequestThatWouldLeaveTheMachine(t *testing.T) {
	ft := &fatalT{TB: t}
	func() {
		defer func() { _ = recover() }()
		Scenario(ft, Config{GatewayURL: "https://psp-gateway.example.com"}, "x", "", func(s *Session) {
			s.Gateway("x", http.MethodGet, "/", nil, nil)
		})
	}()
	if !ft.fatal {
		t.Fatal("a request off the machine was not refused")
	}
}

type fatalT struct {
	testing.TB
	fatal bool
}

func (f *fatalT) Fatalf(string, ...any) { f.fatal = true; panic("fatal") }
func (f *fatalT) Helper()               {}
