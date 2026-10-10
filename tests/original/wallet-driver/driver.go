// Package walletdriver drives the original's wallet-service at its own routes and records each
// scenario as one recording (spec 002 research R13; specs/001-brands-and-projects/contracts/recording.md).
//
// The original's end-to-end suite reaches the wallet only through casino-service and the ST8
// adapter, so its recordings belong to those services. This driver calls the wallet directly, as
// casino-service and the ST8 adapter do, on the `/internal/v1/wallet/{brand}/{player}/{currency}`
// routes (the same handlers as the player-token `/v1/wallet/...` routes, authenticated by the
// brand's HMAC instead of a player's token). Setup the wallet cannot do for itself — the wallet and
// player twins casino-service creates at registration — goes to substrate directly, as
// casino-service does, and is recorded as `setup:` steps a replay skips.
//
// The machinery in this file needs no running original, so `go test ./original/wallet-driver/...`
// checks it against a stub; the scenarios themselves are tagged `e2e`.
package walletdriver

import (
	"bytes"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// Config is where the original runs and where recordings go.
type Config struct {
	WalletURL    string // the original's wallet-service, e.g. http://localhost:8081
	SubstrateURL string // the original's substrate, e.g. http://localhost:8080
	Brand        string // the original's seeded brand, e.g. spinvibe
	Secret       string // WALLET_INTERNAL_SECRET; the HMAC key for X-Internal-Secret
	RecordTo     string // EITHEROR_RECORD_TO; empty runs without writing
	Commit       string // EITHEROR_ORIGINAL_COMMIT
	Client       *http.Client
}

// Response is what the original answered to one step.
type Response struct {
	Status int
	Body   map[string]any
	Raw    []byte
}

// Num reads a JSON field as a string, whatever its JSON type, or "" when absent.
func (r Response) Str(field string) string {
	v, ok := r.Body[field]
	if !ok || v == nil {
		return ""
	}
	switch x := v.(type) {
	case string:
		return x
	case float64:
		return fmt.Sprintf("%v", x)
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}

// Session is one scenario on one fresh player.
type Session struct {
	t        testing.TB
	cfg      Config
	rec      *recorder.Recording
	Player   string
	Currency string
	ids      map[string]string // name -> the value minted for it this run
	order    []string          // names, longest value first when templating
}

// Scenario runs body on a fresh player and writes one recording named exactly `name`.
func Scenario(t *testing.T, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 30 * time.Second}
	}
	s := &Session{
		t:        t,
		cfg:      cfg,
		Player:   newUUID(),
		Currency: "EUR",
		ids:      map[string]string{},
		rec: &recorder.Recording{
			Scenario:    name,
			Requirement: requirement,
			RecordedAt:  time.Now().UTC(),
			Original:    map[string]string{"commit": cfg.Commit, "brand": cfg.Brand},
			Runs:        1,
			Service:     "wallet",
		},
	}
	defer s.write()
	body(s)
}

// ID mints a fresh value for a name the scenario uses (a grant, a payment, a round, a key) and
// records it as `{name}` in every request, so a replay mints its own.
func (s *Session) ID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := name + "-" + newUUID()[:8]
	s.ids[name] = v
	s.order = append(s.order, name)
	return v
}

// UUID mints a fresh UUID for a name, for the original's fields that must be UUID-shaped.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := newUUID()
	s.ids[name] = v
	s.order = append(s.order, name)
	return v
}

// Capture names a value the original generated in the last response, for later steps' `{name}`.
func (s *Session) Capture(name, jsonPath string, value string) {
	if value == "" {
		s.t.Errorf("capture %s: %s was empty in the last response", name, jsonPath)
		return
	}
	last := &s.rec.Steps[len(s.rec.Steps)-1]
	if last.Capture == nil {
		last.Capture = map[string]string{}
	}
	last.Capture[name] = jsonPath
	s.ids[name] = value
	s.order = append(s.order, name)
}

// Wallet is the path of a wallet route on this session's player.
func (s *Session) Wallet(verb string) string {
	return fmt.Sprintf("/internal/v1/wallet/%s/%s/%s/%s", s.cfg.Brand, s.Player, s.Currency, verb)
}

// Post calls the wallet with a JSON body.
func (s *Session) Post(name, path string, body any) Response {
	return s.call(name, "wallet", http.MethodPost, s.cfg.WalletURL, path, body, true)
}

// Get calls the wallet.
func (s *Session) Get(name, path string) Response {
	return s.call(name, "wallet", http.MethodGet, s.cfg.WalletURL, path, nil, true)
}

// Setup calls substrate for what casino-service would have created; recorded as a `setup:` step.
func (s *Session) Setup(name, method, path string, body any) Response {
	return s.call("setup: "+name, "substrate", method, s.cfg.SubstrateURL, path, body, false)
}

// Want records a mismatch between the original's status and the one the feature expects, as a
// failure of the scenario: the recording is still written, and suite-result.json shows it.
func (s *Session) Want(r Response, status int) {
	if r.Status != status {
		s.t.Errorf("%s: the original answered %d (want %d): %s", s.rec.Scenario, r.Status, status, string(r.Raw))
	}
}

func (s *Session) call(name, service, method, base, path string, body any, internal bool) Response {
	s.t.Helper()
	u, err := url.Parse(base + path)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !recorder.Local(u.Host) {
		s.t.Fatalf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var payload []byte
	if body != nil {
		payload, _ = json.Marshal(body)
	}
	req, _ := http.NewRequest(method, u.String(), bytes.NewReader(payload))
	headers := map[string]string{}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
		headers["Content-Type"] = "application/json"
	}
	if internal {
		req.Header.Set("X-Internal-Brand-Id", s.cfg.Brand)
		req.Header.Set("X-Internal-Secret", Signature(s.cfg.Secret, s.cfg.Brand))
		headers["X-Internal-Brand-Id"] = "{brand}" // the secret is never recorded
	}
	resp, err := s.cfg.Client.Do(req)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	raw, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	out := Response{Status: resp.StatusCode, Raw: raw, Body: map[string]any{}}
	_ = json.Unmarshal(raw, &out.Body)

	step := recorder.Step{
		Name: name,
		Request: recorder.Request{
			Method:  method,
			Path:    s.template(u.RequestURI()),
			Service: service,
			Headers: headers,
		},
		Response: recorder.Response{Status: resp.StatusCode, Body: rawJSON(raw)},
	}
	if payload != nil {
		step.Request.Body = json.RawMessage(s.template(string(payload)))
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return out
}

// template replaces this run's player, brand and minted ids with their `{name}` placeholders.
func (s *Session) template(text string) string {
	names := append([]string(nil), s.order...)
	sort.SliceStable(names, func(i, j int) bool { return len(s.ids[names[i]]) > len(s.ids[names[j]]) })
	for _, n := range names {
		text = strings.ReplaceAll(text, s.ids[n], "{"+n+"}")
	}
	text = strings.ReplaceAll(text, s.Player, "{player}")
	return brandSegment.ReplaceAllString(text, "/${1}{brand}/")
}

var brandSegment = regexp.MustCompile(`/(internal/v1/wallet/|twins/casino-)` + `[^/]+/`)

func (s *Session) write() {
	if s.cfg.RecordTo == "" {
		return
	}
	path := filepath.Join(s.cfg.RecordTo, "wallet", FileName(s.rec.Scenario)+".json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
		return
	}
	b, _ := json.MarshalIndent(s.rec, "", "  ")
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
	}
}

// FileName is a scenario's file name: its words, with anything a path cannot hold made a dash.
// The merge tool keys on the recording's `scenario`, which stays the scenario's exact name.
func FileName(scenario string) string {
	return strings.Trim(pathUnsafe.ReplaceAllString(scenario, "-"), "-")
}

var pathUnsafe = regexp.MustCompile(`[^A-Za-z0-9_.-]+`)

// Signature is the original's X-Internal-Secret: HMAC-SHA256 of the brand under the shared secret.
func Signature(secret, brand string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(brand))
	return hex.EncodeToString(m.Sum(nil))
}

// Decimal renders minor units as the original's major-unit string: 1050 -> "10.50".
func Decimal(minor int64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return fmt.Sprintf("%s%d.%02d", sign, minor/100, minor%100)
}

// WalletTwinID is the original's wallet twin id (WalletServiceClient.walletTwinId).
func WalletTwinID(brand, player, currency string) string {
	return nameUUID(fmt.Sprintf("%s:%s:%s", brand, player, currency))
}

// nameUUID is Java's UUID.nameUUIDFromBytes: an MD5 name-based (version 3) UUID.
func nameUUID(name string) string {
	h := md5.Sum([]byte(name))
	h[6] = (h[6] & 0x0f) | 0x30
	h[8] = (h[8] & 0x3f) | 0x80
	return formatUUID(h)
}

func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return formatUUID(b)
}

func formatUUID(b [16]byte) string {
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

func rawJSON(b []byte) json.RawMessage {
	b = bytes.TrimSpace(b)
	if len(b) == 0 {
		return nil
	}
	if json.Valid(b) {
		return json.RawMessage(b)
	}
	q, _ := json.Marshal(string(b))
	return q
}
