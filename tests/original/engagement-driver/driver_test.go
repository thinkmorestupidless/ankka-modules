package engagementdriver

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
	signing  = "s3cret-signing"
	internal = "s3cret-internal"
	operator = "operator-token-0000"
	// The brand's stored keys in the stub: none may reach a recording.
	storedTrack   = "stored-track-key-0000"
	storedApp     = "stored-app-key-0000"
	storedWebhook = "stored-webhook-key-0000"
	holdingKey    = "holding-webhook-key-0000"
)

// stub is an original that registers, verifies and signs in, keeps twins, serves the configuration
// routes with masking, checks the webhook's timestamp and signature under the stored key as the
// original does, holds crm-stub's log, and streams frames on push-gateway's and the agent's routes.
type stub struct {
	mu       sync.Mutex
	n        int
	tokens   map[string]string            // sign-in -> player
	twins    map[string]map[string]string // path -> properties
	crm      []map[string]any             // crm-stub's log
	inbox    map[string]int               // player -> items
	seen     map[string]bool              // delivery ids
	targets  int
	internal []string // every X-Internal-Secret the reset route received
	webhooks []string // every webhook's outcome
	streams  []chan string
	logins   chan string
	signins  int
	used     bool   // the agent's ticket has opened a stream
	last     string // the last player signed in
}

func newStub() *stub {
	cfg, _ := json.Marshal(map[string]any{"siteId": "stub-site", "trackApiKey": storedTrack, "appApiKey": storedApp,
		"region": "us", "webhookSigningKey": storedWebhook, "fromEmail": "", "triggers": map[string]any{}})
	hold, _ := json.Marshal(map[string]any{"siteId": "stub-site", "webhookSigningKey": holdingKey})
	return &stub{
		tokens: map[string]string{}, inbox: map[string]int{}, seen: map[string]bool{}, logins: make(chan string, 8),
		twins: map[string]map[string]string{
			"/twins/casino/" + NameUUID("merchant.crm-config/spinvibe"):    {"crm_config": string(cfg)},
			"/twins/eitheror-holding/" + NameUUID("seed.holding.spinvibe"): {"crm_config": string(hold)},
		},
	}
}

func (o *stub) config() map[string]any {
	m := map[string]any{}
	_ = json.Unmarshal([]byte(o.twins["/twins/casino/"+NameUUID("merchant.crm-config/spinvibe")]["crm_config"]), &m)
	return m
}

func (o *stub) key() string {
	m := map[string]any{}
	if raw := o.twins["/twins/eitheror-holding/"+NameUUID("seed.holding.spinvibe")]["crm_config"]; raw != "" {
		_ = json.Unmarshal([]byte(raw), &m)
		return str(m, "webhookSigningKey")
	}
	return str(o.config(), "webhookSigningKey")
}

func (o *stub) record(action, person string, attrs map[string]any) {
	o.crm = append(o.crm, map[string]any{"host": "track", "path": "/api/v2/entity", "headers": map[string]string{"Authorization": "Basic c3R1Yg=="},
		"body": map[string]any{"type": "person", "action": action, "identifiers": map[string]any{"id": person}, "attributes": attrs}})
}

func (o *stub) signal(kind string) {
	for _, c := range o.streams {
		select {
		case c <- kind:
		default:
		}
	}
}

func isMasked(v string) bool { return len(v) > 3 && strings.Trim(v[3:], "*") == "" }

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	if p == "/v1/stream" || strings.HasSuffix(p, "/agent-stream") {
		o.stream(w, r)
		return
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	raw, _ := io.ReadAll(r.Body)
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	reply := func(status int, format string, args ...any) {
		w.WriteHeader(status)
		fmt.Fprintf(w, format, args...)
	}
	switch {
	case p == "/__received":
		b, _ := json.Marshal(o.crm)
		reply(200, "%s", b)
	case strings.HasSuffix(p, "/register"):
		o.n++
		reply(201, `{"playerId":"0000000%d-aaaa-4aaa-8aaa-00000000000%d","status":"pending_email_verification"}`, o.n, o.n)
		o.twins["/twins/casino-spinvibe/"+fmt.Sprintf("0000000%d-aaaa-4aaa-8aaa-00000000000%d", o.n, o.n)] = map[string]string{}
	case strings.HasSuffix(p, "/verify-email"):
		reply(200, `{"status":"verified"}`)
	case strings.HasSuffix(p, "/login"):
		// A player signs in after registering: the stub's sign-in is its latest player's.
		o.signins++
		id := fmt.Sprintf("0000000%d-aaaa-4aaa-8aaa-00000000000%d", o.n, o.n)
		token := fmt.Sprintf("jwt-%d-secretpart", o.signins)
		o.tokens[token], o.last = id, id
		select {
		case o.logins <- id:
		default:
		}
		reply(200, `{"token":%q,"refreshToken":"rt-%d-secretpart"}`, token, o.signins)
	case p == "/v1/casino/crm-config/spinvibe" && r.Method == http.MethodGet:
		m := o.config()
		for _, k := range []string{"trackApiKey", "appApiKey", "webhookSigningKey"} {
			m[k] = Mask(str(m, k))
		}
		b, _ := json.Marshal(m)
		reply(200, "%s", b)
	case p == "/v1/casino/crm-config/spinvibe" && r.Method == http.MethodPut:
		stored := o.config()
		for _, k := range []string{"trackApiKey", "appApiKey", "webhookSigningKey"} {
			if isMasked(str(body, k)) {
				body[k] = stored[k]
			}
		}
		b, _ := json.Marshal(body)
		o.twins["/twins/casino/"+NameUUID("merchant.crm-config/spinvibe")]["crm_config"] = string(b)
		reply(200, `{"ok":true}`)
	case p == "/v1/casino/crm-config/spinvibe/seed":
		for i := 0; i < 30; i++ {
			o.record("identify", "_seed_spinvibe", map[string]any{"brand_id": "spinvibe"})
			o.record("event", "_seed_spinvibe", map[string]any{})
		}
		reply(200, `{"seeded":30,"person":"_seed_spinvibe"}`)
	case strings.HasPrefix(p, "/v1/casino/customerio/webhook/"):
		ts, sig := r.Header.Get("X-CIO-Timestamp"), r.Header.Get("X-CIO-Signature")
		outcome := func(status int, text string) {
			o.webhooks = append(o.webhooks, text)
			reply(status, "%s", text)
		}
		switch {
		case o.key() == "":
			outcome(401, `{"error":"webhook_not_configured"}`)
		case ts == "":
			outcome(401, `{"error":"timestamp_invalid"}`)
		case sig != CIOSignature(o.key(), ts, raw):
			outcome(401, `{"error":"invalid_signature"}`)
		case body["action"] == "grant_bonus":
			outcome(422, `{"error":"offer_not_found"}`)
		case body["action"] == "target_offer":
			if o.targets++; o.targets == 1 {
				outcome(422, `{"error":"role_not_found"}`)
				return
			}
			outcome(200, `{"ok":true}`)
		case body["action"] == "send_message":
			id, _ := body["player_id"].(string)
			d, _ := body["delivery_id"].(string)
			props, known := o.twins["/twins/casino-spinvibe/"+id]
			switch {
			case d == "":
				outcome(422, `{"error":"delivery_id_required"}`)
			case !known:
				outcome(422, `{"error":"player_not_found"}`)
			case props["message_suppressed"] == "true":
				outcome(422, `{"error":"marketing_suppressed"}`)
			case o.seen[d]:
				outcome(200, `{"ok":true,"alreadyProcessed":true}`)
			default:
				o.seen[d] = true
				o.inbox[id]++
				outcome(200, `{"ok":true}`)
			}
		default:
			outcome(422, `{"error":"unknown_action"}`)
		}
	case strings.HasSuffix(p, "/daily-deposit-count/reset"):
		o.internal = append(o.internal, r.Header.Get("X-Internal-Secret"))
		today := DepositDay(time.Now())
		reset := 0
		for path, props := range o.twins {
			if strings.HasPrefix(path, "/twins/casino-spinvibe/") && props["daily_deposit_count"] != "" && props["daily_deposit_count"] != "0" && props["daily_deposit_count_day"] != today {
				props["daily_deposit_count"], props["daily_deposit_count_day"] = "0", today
				o.record("identify", strings.TrimPrefix(path, "/twins/casino-spinvibe/"), map[string]any{"daily_deposit_count": 0})
				reset++
			}
		}
		reply(200, `{"scanned":%d,"reset":%d,"failed":0}`, o.n, reset)
	case strings.HasSuffix(p, "/vip-tier"):
		id := strings.Split(p, "/")[5]
		o.record("identify", id, map[string]any{"vip_tier": body["tier"]})
		reply(200, `{"vipTier":%q}`, body["tier"])
	case strings.HasSuffix(p, "/messages") && strings.Contains(p, "/backoffice/"):
		o.signal("inbox.changed")
		reply(201, `{"notificationId":"msg-1","state":"unread","createdAt":"2026-11-08T00:00:00Z","subject":"Your account","body":"x"}`)
	case strings.HasSuffix(p, "/messages") && r.Method == http.MethodGet:
		id := strings.Split(p, "/")[5]
		reply(200, `{"items":[%s],"total":%d,"unreadCount":%d}`, map[bool]string{true: `{"originName":"Customer.io campaign","state":"sent"}`}[o.inbox[id] > 0], o.inbox[id], o.inbox[id])
	case strings.HasSuffix(p, "/read"):
		o.signal("inbox.changed")
		reply(200, `{"ok":true}`)
	case strings.HasSuffix(p, "/bonuses"):
		reply(200, `[]`)
	case p == "/v1/presence":
		reply(200, `{"podId":"push-gateway-0","subjects":{"casino-spinvibe/%s":{"connections":1}}}`, o.last)
	case strings.HasSuffix(p, "/players") && strings.Contains(p, "/backoffice/"):
		reply(200, `{"items":[{"playerId":%q,"online":true,"lastSeenAt":"2026-11-08T00:00:00Z"}],"total":1}`, o.last)
	case strings.HasSuffix(p, "/agent-stream/ticket"):
		reply(200, `{"ticket":"ticket-secret-1","expires_in":30}`)
	case strings.HasPrefix(p, "/twins/") && r.Method == http.MethodGet:
		props, ok := o.twins[p]
		if !ok {
			reply(404, `{"error":"not_found"}`)
			return
		}
		b, _ := json.Marshal(map[string]any{"properties": props})
		reply(200, "%s", b)
	case strings.HasPrefix(p, "/twins/"):
		if o.twins[p] == nil {
			o.twins[p] = map[string]string{}
		}
		for k, v := range body {
			o.twins[p][k] = fmt.Sprint(v)
		}
		reply(201, `{}`)
	default:
		reply(200, `{}`)
	}
}

// stream serves push-gateway's /v1/stream (ready, a heartbeat, a signal per message, closing as
// recycled after a second and a half) and the agent's stream (a player online frame when a player signs
// in; the ticket is single use).
func (o *stub) stream(w http.ResponseWriter, r *http.Request) {
	flush := func(format string, args ...any) {
		fmt.Fprintf(w, format, args...)
		w.(http.Flusher).Flush()
	}
	if strings.HasSuffix(r.URL.Path, "/agent-stream") {
		o.mu.Lock()
		used := r.URL.Query().Get("ticket") != "ticket-secret-1" || o.used
		o.used = true
		o.mu.Unlock()
		if used {
			w.WriteHeader(403)
			fmt.Fprint(w, `{"error":"invalid_ticket"}`)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		flush(": ping\n\n")
		select {
		case id := <-o.logins:
			flush("id: session-1\nevent: player_online\ndata: {\"player_id\":%q,\"player_email_prefix\":\"pla\",\"session_id\":\"session-1\",\"occurred_at\":\"2026-11-08T00:00:00Z\"}\n\n", id)
		case <-time.After(2 * time.Second):
		}
		<-r.Context().Done()
		return
	}
	o.mu.Lock()
	player, ok := o.tokens[strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")]
	signals := make(chan string, 4)
	if ok {
		o.streams = append(o.streams, signals)
	}
	o.mu.Unlock()
	if !ok {
		w.WriteHeader(403)
		fmt.Fprint(w, `{"error":"subject_unresolved"}`)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	flush("event: ready\ndata: {\"subjectId\":\"casino-spinvibe/%s\",\"serverTime\":\"2026-11-08T00:00:00Z\"}\n\n", player)
	flush(": heartbeat 2026-11-08T00:00:15Z\n\n")
	recycle := time.After(1500 * time.Millisecond)
	for {
		select {
		case kind := <-signals:
			flush("event: signal\nid: spinvibe:msg-1\ndata: {\"kind\":%q,\"correlationId\":\"spinvibe:msg-1\"}\n\n", kind)
		case <-recycle:
			flush("event: closing\ndata: {\"reason\":\"connection_recycled\"}\n\n")
			return
		case <-r.Context().Done():
			return
		}
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

func stubConfig(srv *httptest.Server, dir string) Config {
	return Config{CasinoURL: srv.URL, KeycloakURL: srv.URL, SubstrateURL: srv.URL, PushURL: srv.URL, CRMStubURL: srv.URL,
		Brand: "spinvibe", SigningSecret: signing, InternalSecret: internal, OperatorToken: operator,
		RecordTo: dir, Commit: "abc1234", Patience: 300 * time.Millisecond, StreamPatience: 2 * time.Second, RecycleWait: 3 * time.Second}
}

func record(t *testing.T, def Def) (recorder.Recording, string, *stub, []string) {
	t.Helper()
	o := newStub()
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	run := &lenient{T: t}
	Scenario(run, stubConfig(srv, dir), def.Scenario(), def.Requirement, func(s *Session) { def.Run(run, s) })
	b, err := os.ReadFile(filepath.Join(dir, "engagement", FileName(def.Scenario())+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var rec recorder.Recording
	if err := json.Unmarshal(b, &rec); err != nil {
		t.Fatal(err)
	}
	return rec, string(b), o, run.notes
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

func stepAt(t *testing.T, rec recorder.Recording, name string) int {
	t.Helper()
	for i, s := range rec.Steps {
		if s.Name == name {
			return i
		}
	}
	t.Fatalf("no step %q", name)
	return -1
}

// compact is a recorded body on one line: the recording indents it.
func compact(b json.RawMessage) string {
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		return string(b)
	}
	return buf.String()
}

func frames(t *testing.T, s recorder.Step) []Frame {
	t.Helper()
	var fs []Frame
	if err := json.Unmarshal(s.Response.Body, &fs); err != nil {
		t.Fatalf("step %q: the answer is not an array of frames: %s", s.Name, s.Response.Body)
	}
	return fs
}

func TestTheOriginalsNameUUIDsDepositDayMaskAndWebhookSignature(t *testing.T) {
	// Computed outside Go: MD5 of the name with the version 3 and IETF variant bits, as Java's
	// UUID.nameUUIDFromBytes makes it.
	if got := NameUUID("seed.sub-brand.spinvibe"); got != "2867b301-c32d-3879-b766-10321f0302a8" {
		t.Errorf("got %s", got)
	}
	// `printf 'v0:1700000000:{}' | openssl dgst -sha256 -hmac key`
	if got := CIOSignature("key", "1700000000", []byte("{}")); got != "fd9fa6c53274153a1b20c4777f83ea49e617d4b58da089ca0e8ae76300d03970" {
		t.Errorf("signature %s", got)
	}
	for at, want := range map[string]string{"2026-11-08T02:59:59Z": "2026-11-07", "2026-11-08T03:00:00Z": "2026-11-08", "2026-11-08T23:00:00Z": "2026-11-08"} {
		ts, _ := time.Parse(time.RFC3339, at)
		if got := DepositDay(ts); got != want {
			t.Errorf("DepositDay(%s) = %s, want %s", at, got, want)
		}
	}
	for v, want := range map[string]string{"": "", "abc": "abc", "sk-abc123": "sk-******", "appKey-1234": "app********"} {
		if got := Mask(v); got != want {
			t.Errorf("Mask(%q) = %q, want %q", v, got, want)
		}
	}
}

// Every scenario runs against the stub, and none writes a secret, the brand's stored keys, crm-stub's
// credentials or the webhook's signature anywhere, nor sends a token or an address as it is. A
// sign-in's answer keeps its token, as every driver's does; the merge redacts it.
func TestEveryScenarioRecordsNoCredentialAndNamesEveryTokenItCarries(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			rec, text, _, _ := record(t, d)
			if rec.Service != "engagement" || rec.Requirement != d.Requirement || rec.Original["commit"] != "abc1234" || len(rec.Steps) == 0 {
				t.Fatalf("header: %+v", rec)
			}
			for _, raw := range []string{signing, internal, storedTrack, storedApp, storedWebhook, holdingKey, "c3R1Yg==",
				"X-CIO-Signature", "X-CIO-Timestamp", "X-Internal-Secret"} {
				if strings.Contains(text, raw) {
					t.Errorf("the recording carries %q", raw)
				}
			}
			for _, s := range rec.Steps {
				sent := s.Request.Path + string(s.Request.Body) + fmt.Sprint(s.Request.Headers)
				for _, raw := range []string{operator, "secretpart", "@example.test", "ticket-secret-1"} {
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

func TestEveryScenarioPutsTheBrandsStoredConfigurationBack(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			before := newStub()
			_, _, o, _ := record(t, d)
			for _, twin := range []string{"/twins/casino/" + NameUUID("merchant.crm-config/spinvibe"), "/twins/eitheror-holding/" + NameUUID("seed.holding.spinvibe")} {
				if got, want := o.twins[twin]["crm_config"], before.twins[twin]["crm_config"]; got != want {
					t.Errorf("%s holds %s after the scenario, want %s", twin, got, want)
				}
			}
		})
	}
}

func TestEveryScenarioMeetsItsFeatureAgainstAStubThatBehavesAsTheOriginal(t *testing.T) {
	for _, d := range Scenarios() {
		d := d
		t.Run(d.Scenario(), func(t *testing.T) {
			if _, _, _, notes := record(t, d); len(notes) != 0 {
				t.Errorf("the scenario's expectations failed against the stub: %v", notes)
			}
		})
	}
}

func TestTheWebhookIsSignedWithTheKeyTheBrandHoldsAndTheVariantsAreNamedNotRecorded(t *testing.T) {
	rec, _, o, _ := record(t, find(t, "a send action with a delivery id, a title and a body writes one inbox item of the marketing class, and a repeat writes nothing"))
	if want := []string{`{"ok":true}`, `{"ok":true,"alreadyProcessed":true}`, `{"error":"marketing_suppressed"}`, `{"error":"player_not_found"}`}; fmt.Sprint(o.webhooks) != fmt.Sprint(want) {
		t.Errorf("the webhook answered %v", o.webhooks)
	}
	send := step(t, rec, "the CRM sends the player a message")
	if send.Request.Path != "/v1/casino/customerio/webhook/spinvibe" || len(send.Request.Headers) != 1 || compact(send.Request.Body) !=
		`{"action":"send_message","body":"Something new is waiting in the lobby.","campaign_id":"{campaignId}","delivery_id":"{deliveryId}","player_id":"{playerId}","title":"A message from your casino"}` {
		t.Errorf("send: %+v %s", send.Request, send.Request.Body)
	}
	key := step(t, rec, "setup: the brand's webhook signing key")
	if b := compact(key.Request.Body); key.Request.Method != http.MethodPut || key.Request.Path != "/v1/casino/crm-config/spinvibe" ||
		!strings.Contains(b, `"webhookSigningKey":"`+WebhookKey+`"`) || !strings.Contains(b, `"appApiKey":"sto****`) {
		t.Errorf("key: %+v %s", key.Request, b)
	}
	for example, want := range map[string]string{
		"a brand with a signing key for its CRM webhook and a request with no signature":          "invalid_signature",
		"a brand with a signing key for its CRM webhook and a request with a malformed signature": "invalid_signature",
		"a brand with a signing key for its CRM webhook and a request with a wrong signature":     "invalid_signature",
		"a brand with no signing key for its CRM webhook":                                         "webhook_not_configured",
	} {
		_, _, o, _ := record(t, find(t, "a request whose signature is missing, malformed or wrong, or for a brand with no signing key, is refused as unauthenticated and does nothing ["+example+"]"))
		if len(o.webhooks) != 1 || !strings.Contains(o.webhooks[0], want) {
			t.Errorf("%s: the webhook answered %v", example, o.webhooks)
		}
	}
}

func TestTheMaskedAppKeySentBackKeepsTheKeySaved(t *testing.T) {
	rec, _, o, _ := record(t, find(t, "a new CRM app key is stored in the secret store and read back masked, and a masked value sent back preserves the secret"))
	save := step(t, rec, "the operator saves a new app key")
	if b := compact(save.Request.Body); !strings.Contains(b, `"appApiKey":"{appKey}"`) || !strings.Contains(b, `"trackApiKey":"sto*****`) {
		t.Errorf("save: %s", b)
	}
	back := step(t, rec, "the operator saves the page unchanged, the app key masked")
	if b := compact(back.Request.Body); !strings.Contains(b, `"appApiKey":"app`+strings.Repeat("*", 12)+`"`) {
		t.Errorf("back: %s", b)
	}
	// The stub restored what it held; the scenario read the key it saved before that.
	if str(o.config(), "appApiKey") != storedApp {
		t.Errorf("not restored: %v", o.config())
	}
}

func TestTheResetSendsTheInternalSecretUnrecordedAndWritesTheDaysByName(t *testing.T) {
	rec, _, o, _ := record(t, find(t, "the deposit day rolling over identifies a player who deposited today with a daily deposit count of zero, and not one already at zero"))
	if len(o.internal) != 1 || o.internal[0] != internal {
		t.Errorf("the reset received %v", o.internal)
	}
	reset := step(t, rec, "the deposit day rolls over")
	if reset.Request.Path != "/internal/v1/casino/spinvibe/daily-deposit-count/reset" || reset.Request.Headers != nil {
		t.Errorf("reset: %+v", reset.Request)
	}
	count := step(t, rec, "setup: the player deposited twice on the deposit day ending")
	if compact(count.Request.Body) != `{"daily_deposit_count":"2","daily_deposit_count_day":"{yesterday}"}` {
		t.Errorf("count: %s", count.Request.Body)
	}
}

func TestAStreamIsOneStepAtItsOpeningWhoseAnswerIsItsFrames(t *testing.T) {
	rec, _, _, _ := record(t, find(t, "an inbox item created reaches each of the player's connections on two instances as one inbox changed push, and one delivered fact counts them"))
	a := stepAt(t, rec, "the player's first device opens the stream")
	b := stepAt(t, rec, "the player's second device opens the stream")
	m := stepAt(t, rec, "an operator sends the player an inbox item")
	if !(a < b && b < m) {
		t.Errorf("steps at %d, %d, %d", a, b, m)
	}
	st := rec.Steps[a]
	if st.Request.Service != "push" || st.Request.Path != "/v1/stream" || st.Request.Headers["Authorization"] != "Bearer {token}" ||
		st.Request.Headers["Accept"] != "text/event-stream" || st.Response.Status != 200 {
		t.Errorf("stream: %+v", st)
	}
	fs := frames(t, st)
	if len(fs) < 3 || fs[0].Event != "ready" || !strings.HasPrefix(fs[1].Comment, "heartbeat") || fs[2].Event != "signal" ||
		fs[2].ID != "spinvibe:msg-1" || fs[2].Field("kind") != "inbox.changed" {
		t.Errorf("frames: %+v", fs)
	}
	refused, _, _, _ := record(t, find(t, "an operator's or a part's sign-in without a player id is refused the stream"))
	if r := refused.Steps[0]; r.Response.Status != 403 || compact(r.Response.Body) != `{"error":"subject_unresolved"}` {
		t.Errorf("refused: %+v %s", r.Response, r.Response.Body)
	}
}

func TestTheAgentStreamCarriesItsTicketByNameAndRecordsTheSignInBetweenItsFrames(t *testing.T) {
	rec, _, _, _ := record(t, find(t, "an agent's stream opened with a ticket is told when a player signs in, and the used ticket is refused"))
	open := stepAt(t, rec, "the agent's page opens the stream with the ticket")
	login := stepAt(t, rec, "the player signs in")
	if open > login || rec.Steps[open].Request.Path != "/v1/casino/backoffice/spinvibe/agent-stream?ticket={ticket}" || rec.Steps[open].Request.Headers["Authorization"] != "" {
		t.Errorf("open: %+v", rec.Steps[open].Request)
	}
	if f := FirstOf(frames(t, rec.Steps[open]), "player_online", "", ""); f == nil {
		t.Error("no player_online frame recorded")
	}
	if again := step(t, rec, "the agent's page opens the stream with the same ticket"); again.Response.Status != 403 {
		t.Errorf("again: %+v", again.Response)
	}
}

// featureScenarios is every scenario of features/engagement/*.feature, an outline's once per example
// by its first column.
func featureScenarios(t *testing.T) []string {
	files, err := filepath.Glob(filepath.Join("..", "..", "..", "features", "engagement", "*.feature"))
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
	known := map[string]bool{}
	for _, n := range featureScenarios(t) {
		known[n] = true
	}
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Scenario()] {
			t.Errorf("two scenarios named %q", d.Scenario())
		}
		driven[d.Scenario()] = true
		if !strings.HasPrefix(d.Requirement, "014/FR-") {
			t.Errorf("%q: requirement %q", d.Scenario(), d.Requirement)
		}
		switch {
		case d.Unfeatured == "" && !known[d.Scenario()]:
			t.Errorf("no feature names a scenario %q", d.Scenario())
		case d.Unfeatured != "" && known[d.Scenario()]:
			t.Errorf("%q is a feature's scenario and says no feature names it", d.Scenario())
		}
	}
	for n := range known {
		if !driven[n] && NotDriven[n] == "" {
			t.Errorf("%q is neither driven nor said why not", n)
		}
		if driven[n] && NotDriven[n] != "" {
			t.Errorf("%q is driven and said not to be", n)
		}
	}
	for n := range NotDriven {
		if !known[n] {
			t.Errorf("NotDriven names %q, which no feature does", n)
		}
	}
}
