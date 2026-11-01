// Package programmedriver drives the original's affiliate-service at the routes spec 013's shared
// `programme` takes over (the operator's `/v1/affiliates/{brand}/…`, the portal's
// `/v1/my/{affiliateId}/…`, the public application and join routes, and the internal `notify/*`
// routes), and records each scenario of specs/013-affiliate-programme/contracts/recording.md as one
// recording, named by the feature scenario of features/programme/*.feature it shows.
//
// The original's suites never reach the programme side: its Go suite stops at a CPA count in a
// revenue twin (scenario 17), and its Playwright smoke tests are skipped without a live stack. What
// this driver sends is what the backoffice and the portal send: an application and its approval or
// rejection, plans, accounts, links, bonus codes, invitations, the network, adjustments, the close and
// the payout's status machine, managers, fraud flags, notes, settings, creatives and notifications,
// with the brand's facts arriving as casino-service sends them to `notify/*`.
//
// Every scenario runs on the original's seeded brand (the staff sign-in holds `brand_<brand>`, and an
// affiliate's login takes `brand_<brand>`, a realm role that exists only for a seeded brand), on a
// run token `{run}` that makes its emails, plan names and codes its own. Brand-wide lists (plans,
// payouts, flags, creatives, managers, the close's answer) therefore also hold earlier runs' rows:
// the driver checks only its own, and two runs' merge marks the rest as varying.
//
// Tokens are the run's own and are written as `Bearer {name}`: the staff member's `{staffToken}` and
// each affiliate's `{<who>Token}`. The internal secret, the realm admin's token and every password
// are never written. An affiliate's password is set out of band through Keycloak's admin API (SignIn),
// since the original's own way, the accept-invitation route, needs the token it only emails.
//
// The machinery needs no running original, so `go test ./original/programme-driver/...` checks it
// and the scenario names against a stub; the run itself is tagged `e2e`.
package programmedriver

import (
	"bytes"
	"crypto/md5"
	"crypto/rand"
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
	"sync"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// Config is where the original runs, who the run acts as, and where recordings go.
type Config struct {
	AffiliateURL   string // the original's affiliate-service, e.g. http://localhost:8093 (its KinD NodePort)
	KeycloakURL    string // the original's Keycloak, e.g. http://localhost:8180
	Realm          string // the realm both of the service's validators read, `eitheror`
	PortalClient   string // the client an affiliate signs in through, `affiliate-portal` (the service checks `azp`)
	Brand          string // the original's seeded brand, `spinvibe`; written as {brand}
	InternalSecret string // the service's internal secret; sent as X-Internal-Secret, never recorded
	StaffToken     string // a staff member holding brand_admin and brand_<brand>; written as {staffToken}
	AdminToken     string // a master-realm admin token, for setting an affiliate's password; never written
	RecordTo       string // EITHEROR_RECORD_TO; empty runs without writing
	Commit         string // EITHEROR_ORIGINAL_COMMIT
	Client         *http.Client
	Now            func() time.Time
}

// Service is the port the recordings are for, and their directory.
const Service = "programme"

// Response is what the original answered to one step.
type Response struct {
	Status int
	Body   map[string]any // the answer when it is a JSON object
	Value  any            // the answer as JSON, whatever its shape
	Raw    []byte
}

// Str reads a top-level JSON field as a string, whatever its JSON type, or "" when absent.
func (r Response) Str(field string) string { return str(r.Body[field]) }

// Field reads the first field of that name anywhere in the answer, depth first, as a string: the
// original nests its revenue figures differently per route.
func (r Response) Field(name string) string {
	var walk func(v any) (string, bool)
	walk = func(v any) (string, bool) {
		switch x := v.(type) {
		case map[string]any:
			if f, ok := x[name]; ok {
				return str(f), true
			}
			keys := make([]string, 0, len(x))
			for k := range x {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			for _, k := range keys {
				if s, ok := walk(x[k]); ok {
					return s, true
				}
			}
		case []any:
			for _, e := range x {
				if s, ok := walk(e); ok {
					return s, true
				}
			}
		}
		return "", false
	}
	s, _ := walk(r.Value)
	return s
}

// Find is the first element of a list in the answer (the answer itself, or one of its top-level
// fields) whose `field` is `value`, and its JSON path in the subset the replayer reads (`$`, `.name`,
// `[index]`); nil when there is none.
func (r Response) Find(field, value string) (map[string]any, string) {
	look := func(list []any, at string) (map[string]any, string) {
		for i, e := range list {
			if m, ok := e.(map[string]any); ok && str(m[field]) == value {
				return m, fmt.Sprintf("%s[%d]", at, i)
			}
		}
		return nil, ""
	}
	switch x := r.Value.(type) {
	case []any:
		return look(x, "$")
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if list, ok := x[k].([]any); ok {
				if m, at := look(list, "$."+k); m != nil {
					return m, at
				}
			}
		}
	}
	return nil, ""
}

func str(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64, bool:
		return fmt.Sprintf("%v", x)
	default:
		b, _ := json.Marshal(x)
		return string(b)
	}
}

// Auth names who a step acts as: the run value remembered under that name is sent as a bearer
// token and recorded as `Bearer {name}`. Internal sends the internal secret and records nothing.
type Auth string

const (
	NoAuth   Auth = ""
	Staff    Auth = "staffToken"
	Internal Auth = "internal"
)

// As is the auth of an affiliate the scenario signed in under `who`.
func As(who string) Auth { return Auth(who + "Token") }

// Session is one scenario on the seeded brand under its own run token.
type Session struct {
	t     testing.TB
	cfg   Config
	rec   *recorder.Recording
	ids   map[string]string
	order []string
}

// Scenario runs body and writes one recording named exactly `name`.
func Scenario(t testing.TB, cfg Config, name, requirement string, body func(s *Session)) {
	t.Helper()
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 30 * time.Second}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Realm == "" {
		cfg.Realm = "eitheror"
	}
	if cfg.PortalClient == "" {
		cfg.PortalClient = "affiliate-portal"
	}
	s := &Session{t: t, cfg: cfg, ids: map[string]string{}, rec: &recorder.Recording{
		Scenario:    name,
		Requirement: requirement,
		RecordedAt:  time.Now().UTC(),
		Original:    map[string]string{"commit": cfg.Commit, "brand": cfg.Brand},
		Runs:        1,
		Service:     Service,
	}}
	s.Remember("brand", cfg.Brand)
	s.Remember("run", NewUUID()[:8])
	month := time.Date(cfg.Now().UTC().Year(), cfg.Now().UTC().Month(), 1, 0, 0, 0, 0, time.UTC)
	s.Remember("period", month.Format("2006-01"))
	s.Remember("previousPeriod", month.AddDate(0, -1, 0).Format("2006-01"))
	s.Remember("nextPeriod", month.AddDate(0, 1, 0).Format("2006-01"))
	s.Remember(string(Staff), cfg.StaffToken)
	defer s.write()
	body(s)
}

// Brand is the seeded brand the run acts on.
func (s *Session) Brand() string { return s.cfg.Brand }

// Email is the address of `who` in this run: `<who>-{run}@example.com`.
func (s *Session) Email(who string) string { return who + "-" + s.Value("run") + "@example.com" }

// UUID mints a fresh UUID under name and records it as `{name}` in every later request, so a replay
// mints its own.
func (s *Session) UUID(name string) string {
	if v, ok := s.ids[name]; ok {
		return v
	}
	v := NewUUID()
	s.Remember(name, v)
	return v
}

// Value is a value minted or captured under name, or "" when there is none.
func (s *Session) Value(name string) string { return s.ids[name] }

// Remember names a value this run made or read, so every later request carries `{name}` in its place.
func (s *Session) Remember(name, value string) {
	if _, ok := s.ids[name]; !ok {
		s.order = append(s.order, name)
	}
	s.ids[name] = value
}

// Grab names a value the original generated in the last answer, for later steps' `{name}`.
func (s *Session) Grab(name, jsonPath, value string) {
	if value == "" {
		s.t.Errorf("%s: capture %s: %s was empty in the last answer", s.rec.Scenario, name, jsonPath)
		return
	}
	last := s.last()
	if last.Capture == nil {
		last.Capture = map[string]string{}
	}
	last.Capture[name] = jsonPath
	s.Remember(name, value)
}

// Vary marks a field of the last answer as one the original mints or stamps, or one the brand's
// earlier runs fill: checked for presence only.
func (s *Session) Vary(jsonPath, why string) {
	last := s.last()
	if last.Vary == nil {
		last.Vary = map[string]string{}
	}
	last.Vary[jsonPath] = why
}

func (s *Session) last() *recorder.Step {
	if len(s.rec.Steps) == 0 {
		s.t.Fatalf("no step yet")
	}
	return &s.rec.Steps[len(s.rec.Steps)-1]
}

// Want records a mismatch between the original's status and the one expected as a failure of the
// scenario; the recording is still written.
func (s *Session) Want(r Response, status int) Response {
	if r.Status != status {
		s.t.Errorf("%s: the original answered %d (want %d): %s", s.rec.Scenario, r.Status, status, string(r.Raw))
	}
	return r
}

// WantOK is Want for any 2xx, where the original's exact success status is not the point.
func (s *Session) WantOK(r Response) Response {
	if r.Status < 200 || r.Status > 299 {
		s.t.Errorf("%s: the original answered %d (want 2xx): %s", s.rec.Scenario, r.Status, string(r.Raw))
	}
	return r
}

// WantRefused is Want for a refusal of a target outside the caller's reach: the original answers
// 404 `account_not_found` or 403.
func (s *Session) WantRefused(r Response) Response {
	if r.Status != http.StatusNotFound && r.Status != http.StatusForbidden {
		s.t.Errorf("%s: the original answered %d (want a refusal): %s", s.rec.Scenario, r.Status, string(r.Raw))
	}
	return r
}

// WantField is Want for one top-level field of the answer.
func (s *Session) WantField(r Response, field, value string) Response {
	if got := r.Str(field); got != value {
		s.t.Errorf("%s: the original answered %s %q (want %q): %s", s.rec.Scenario, field, got, value, string(r.Raw))
	}
	return r
}

// WantRow is Want for the row of a list whose `key` is `id`: present, and with `field` at `value`
// when `field` is not empty.
func (s *Session) WantRow(r Response, key, id, field, value string) Response {
	row, _ := r.Find(key, id)
	switch {
	case row == nil:
		s.t.Errorf("%s: no row with %s %q in the answer: %s", s.rec.Scenario, key, id, string(r.Raw))
	case field != "" && str(row[field]) != value:
		s.t.Errorf("%s: the row with %s %q has %s %q (want %q)", s.rec.Scenario, key, id, field, str(row[field]), value)
	}
	return r
}

// WantNoRow is Want for a list that holds no row whose `key` is `id`.
func (s *Session) WantNoRow(r Response, key, id string) Response {
	if row, _ := r.Find(key, id); row != nil {
		s.t.Errorf("%s: a row with %s %q in the answer: %s", s.rec.Scenario, key, id, string(r.Raw))
	}
	return r
}

// Call is one step against affiliate-service, a replay sends to the port and compares.
func (s *Session) Call(name, method, path string, body any, auth Auth) Response {
	return s.call(name, method, path, body, auth)
}

// Staff calls an operator route of the brand: `/v1/affiliates/{brand}<suffix>`.
func (s *Session) Staff(name, method, suffix string, body any) Response {
	return s.call(name, method, "/v1/affiliates/"+s.Brand()+suffix, body, Staff)
}

// Portal calls a portal route as `who`, about the affiliate `about`: `/v1/my/{about}<suffix>`.
func (s *Session) Portal(name, who, about, method, suffix string, body any) Response {
	return s.call(name, method, "/v1/my/"+s.Value(about)+suffix, body, As(who))
}

// Notify posts one of the original's internal notifications for the brand, as casino-service does.
func (s *Session) Notify(name, kind string, body map[string]any) Response {
	return s.call(name, http.MethodPost, "/internal/v1/affiliates/"+s.Brand()+"/notify/"+kind, body, Internal)
}

// Setup is a call that puts the brand or the original where a scenario starts from; recorded as a
// `setup:` step a replay turns into kit setup.
func (s *Session) Setup(name, method, path string, body any, auth Auth) Response {
	return s.call("setup: "+name, method, path, body, auth)
}

// Settled repeats a read until `until` holds of its answer, within 30 seconds, and records only the
// answer that held: the original folds and writes its revenue cache after some of its answers.
func (s *Session) Settled(name, method, path string, auth Auth, until func(Response) bool) Response {
	s.t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for {
		r := s.call(name, method, path, nil, auth)
		if until(r) {
			return r
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		if time.Now().After(deadline) {
			s.t.Fatalf("%s: %s not settled within 30 seconds: %s", s.rec.Scenario, name, string(r.Raw))
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// Race sends the same request n times at once and records the answers in status order, as
// `<name> (i of n)`: which of them the original served first is not the point, how many of each is.
func (s *Session) Race(name string, n int, method, path string, body any, auth Auth) []Response {
	s.t.Helper()
	type result struct {
		resp Response
		step recorder.Step
	}
	results := make([]result, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		req, step := s.request(name, method, path, body, auth)
		wg.Add(1)
		go func(i int, req *http.Request, step recorder.Step) {
			defer wg.Done()
			r, err := s.send(req, &step)
			if err != nil {
				s.t.Errorf("%s: %v", name, err)
			}
			results[i] = result{r, step}
		}(i, req, step)
	}
	wg.Wait()
	sort.SliceStable(results, func(i, j int) bool { return results[i].resp.Status < results[j].resp.Status })
	out := make([]Response, n)
	for i, r := range results {
		r.step.Name = fmt.Sprintf("%s (%d of %d)", name, i+1, n)
		s.rec.Steps = append(s.rec.Steps, r.step)
		out[i] = r.resp
	}
	return out
}

func (s *Session) call(name, method, path string, body any, auth Auth) Response {
	s.t.Helper()
	req, step := s.request(name, method, path, body, auth)
	r, err := s.send(req, &step)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	s.rec.Steps = append(s.rec.Steps, step)
	return r
}

// request builds a request and the step that records it, with the run's values as placeholders and
// no credential: the internal secret is not recorded, a bearer token is recorded by its name.
func (s *Session) request(name, method, path string, body any, auth Auth) (*http.Request, recorder.Step) {
	s.t.Helper()
	u, err := url.Parse(strings.TrimSuffix(s.cfg.AffiliateURL, "/") + path)
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
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
		headers["Content-Type"] = "application/json"
	}
	switch auth {
	case NoAuth:
	case Internal:
		req.Header.Set("X-Internal-Secret", s.cfg.InternalSecret) // never recorded
	default:
		token := s.ids[string(auth)]
		if token == "" {
			s.t.Fatalf("%s: no %s for this run", name, auth)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		headers["Authorization"] = "Bearer {" + string(auth) + "}"
	}
	step := recorder.Step{
		Name:    name,
		Request: recorder.Request{Method: method, Path: s.template(u.RequestURI()), Service: Service, Headers: headers},
	}
	if payload != nil {
		step.Request.Body = json.RawMessage(s.template(string(payload)))
	}
	return req, step
}

func (s *Session) send(req *http.Request, step *recorder.Step) (Response, error) {
	resp, err := s.cfg.Client.Do(req)
	if err != nil {
		return Response{}, err
	}
	raw, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	out := Response{Status: resp.StatusCode, Raw: raw, Body: map[string]any{}}
	_ = json.Unmarshal(raw, &out.Value)
	if m, ok := out.Value.(map[string]any); ok {
		out.Body = m
	}
	step.Response = recorder.Response{Status: resp.StatusCode}
	if b := rawJSON(raw); len(b) > 0 {
		step.Response.Body = json.RawMessage(s.template(string(b)))
	}
	return out, nil
}

// template replaces this run's minted and captured values with their `{name}` placeholders, longest
// first; a value also appears URL-escaped in a path or a query, and is replaced there too.
func (s *Session) template(text string) string {
	names := append([]string(nil), s.order...)
	sort.SliceStable(names, func(i, j int) bool { return len(s.ids[names[i]]) > len(s.ids[names[j]]) })
	for _, n := range names {
		v := s.ids[n]
		if v == "" {
			continue
		}
		text = strings.ReplaceAll(text, v, "{"+n+"}")
		if e := url.QueryEscape(v); e != v {
			text = strings.ReplaceAll(text, e, "{"+n+"}")
		}
	}
	return text
}

func (s *Session) write() {
	if s.cfg.RecordTo == "" {
		return
	}
	path := filepath.Join(s.cfg.RecordTo, Service, FileName(s.rec.Scenario)+".json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
		return
	}
	b, err := json.MarshalIndent(s.rec, "", "  ")
	if err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
		return
	}
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		s.t.Errorf("recording %s: %v", s.rec.Scenario, err)
	}
}

// SignIn gives the affiliate `who` (an account the original has made, and so provisioned in the
// realm with roles `affiliate` and `brand_<brand>`) a password and a sign-in, outside any recording:
// it finds the realm user by the account's email, clears its required actions, sets a password
// through the admin API, and signs in through the portal's client. The sign-in is then `{<who>Token}`.
// The original's own way to a password is the accept-invitation route with the token it emails; the
// token store keeps only its hash, so a driver cannot read it.
func (s *Session) SignIn(who string) {
	s.t.Helper()
	users := strings.TrimSuffix(s.cfg.KeycloakURL, "/") + "/admin/realms/" + s.cfg.Realm + "/users"
	email := s.Email(who)
	var id string
	deadline := time.Now().Add(30 * time.Second)
	for id == "" {
		raw, status, err := s.keycloak(http.MethodGet, users+"?exact=true&email="+url.QueryEscape(email), nil)
		if err != nil {
			s.t.Fatalf("sign in %s: %v", who, err)
		}
		var found []struct {
			ID string `json:"id"`
		}
		if status == http.StatusOK && json.Unmarshal(raw, &found) == nil && len(found) > 0 {
			id = found[0].ID
			break
		}
		if time.Now().After(deadline) {
			s.t.Fatalf("sign in %s: the original provisioned no realm user for %s within 30 seconds", who, email)
		}
		time.Sleep(250 * time.Millisecond)
	}
	if _, status, err := s.keycloak(http.MethodPut, users+"/"+id, map[string]any{"emailVerified": true, "requiredActions": []string{}}); err != nil || status/100 != 2 {
		s.t.Fatalf("sign in %s: clearing required actions answered %d: %v", who, status, err)
	}
	password := NewUUID()
	if _, status, err := s.keycloak(http.MethodPut, users+"/"+id+"/reset-password", map[string]any{"type": "password", "value": password, "temporary": false}); err != nil || status/100 != 2 {
		s.t.Fatalf("sign in %s: setting the password answered %d: %v", who, status, err)
	}
	token, err := PasswordToken(s.cfg.Client, s.cfg.KeycloakURL, s.cfg.Realm, s.cfg.PortalClient, email, password)
	if err != nil {
		s.t.Fatalf("sign in %s: %v", who, err)
	}
	s.Remember(string(As(who)), token)
}

func (s *Session) keycloak(method, target string, body any) ([]byte, int, error) {
	u, err := url.Parse(target)
	if err != nil {
		return nil, 0, err
	}
	if !recorder.Local(u.Host) {
		return nil, 0, fmt.Errorf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var payload io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		payload = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, u.String(), payload)
	req.Header.Set("Authorization", "Bearer "+s.cfg.AdminToken)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := s.cfg.Client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	return raw, resp.StatusCode, nil
}

// PasswordToken fetches a token from a realm by the password grant, outside any recording: it is how
// the run obtains the staff member's, the realm admin's and each affiliate's sign-in.
func PasswordToken(client *http.Client, keycloak, realm, clientID, user, password string) (string, error) {
	u, err := url.Parse(strings.TrimSuffix(keycloak, "/") + "/realms/" + realm + "/protocol/openid-connect/token")
	if err != nil {
		return "", err
	}
	if !recorder.Local(u.Host) {
		return "", fmt.Errorf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	form := url.Values{"grant_type": {"password"}, "client_id": {clientID}, "username": {user}, "password": {password}}
	resp, err := client.PostForm(u.String(), form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
	}
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || json.Unmarshal(raw, &out) != nil || out.AccessToken == "" {
		return "", fmt.Errorf("realm %s answered %d for %s", realm, resp.StatusCode, user)
	}
	return out.AccessToken, nil
}

// FileName is a scenario's file name: its words, with anything a path cannot hold made a dash.
func FileName(scenario string) string {
	return strings.Trim(pathUnsafe.ReplaceAllString(scenario, "-"), "-")
}

var pathUnsafe = regexp.MustCompile(`[^A-Za-z0-9_.-]+`)

// NameUUID is Java's UUID.nameUUIDFromBytes: an MD5 name-based (version 3) UUID, as the original
// names its plans, payouts and fraud flags.
func NameUUID(name string) string {
	h := md5.Sum([]byte(name))
	h[6] = (h[6] & 0x0f) | 0x30
	h[8] = (h[8] & 0x3f) | 0x80
	return formatUUID(h)
}

// NewUUID is a random (version 4) UUID.
func NewUUID() string {
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

func rawJSON(b []byte) []byte {
	b = bytes.TrimSpace(b)
	if len(b) == 0 || json.Valid(b) {
		return b
	}
	q, _ := json.Marshal(string(b))
	return q
}
