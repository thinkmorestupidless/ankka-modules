package publicapidriver

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	signing  = "s3cret-signing"
	internal = "internal-secret-0000"
	adminPW  = "keycloak-admin-0000"
)

// clock is the run's time, moved on by its sleeps, so a stub run waits for nothing.
type clock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *clock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.now }
func (c *clock) Sleep(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if d > 0 {
		c.now = c.now.Add(d)
	}
}

type item map[string]any

// stub is an original: casino-service's registration, profile, launch, offer, grant, catalogue
// configuration and internal statistics routes, fake-st8's test hooks, the wallet's signed deposit, and
// the partner API over what they made, brand by brand, as router.ts, server.ts and the handlers answer.
type stub struct {
	mu       sync.Mutex
	clock    *clock
	n        int
	brandOf  map[string]string // player id -> brand
	launches map[string]string // launch token -> session id
	sessions map[string]item
	rounds   map[string]item // round id -> round
	bets     map[string]item
	txns     map[string]item
	awards   map[string]item
	players  map[string]item
	order    []string // every record id in the order made
	offers   map[string]string
	disabled map[string]bool
	snapshot map[string][]string // brand -> snapshots published
	window   time.Time
	used     int
	signed   []bool
	auths    []string
}

func newStub(c *clock) *stub {
	return &stub{clock: c, brandOf: map[string]string{}, launches: map[string]string{}, sessions: map[string]item{},
		rounds: map[string]item{}, bets: map[string]item{}, txns: map[string]item{}, awards: map[string]item{},
		players: map[string]item{}, offers: map[string]string{}, disabled: map[string]bool{}, snapshot: map[string][]string{}}
}

func (o *stub) id() string {
	o.n++
	return fmt.Sprintf("%08d-aaaa-4aaa-8aaa-%012d", o.n, o.n)
}

func (o *stub) add(m map[string]item, r item) {
	id := o.id()
	r["id"] = id
	m[id] = r
	o.order = append(o.order, id)
}

// list is the records of m whose field equals value, in the order made.
func (o *stub) list(m map[string]item, field, value string) []item {
	var out []item
	for _, id := range o.order {
		if r, ok := m[id]; ok && r[field] == value {
			out = append(out, r)
		}
	}
	return out
}

var uuidRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func (o *stub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.auths = append(o.auths, r.Header.Get("Authorization"))
	raw, _ := io.ReadAll(r.Body)
	body := map[string]any{}
	_ = json.Unmarshal(raw, &body)
	p, q := r.URL.Path, r.URL.Query()
	reply := func(status int, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
	parts := strings.Split(strings.Trim(p, "/"), "/")
	switch {
	case strings.HasPrefix(p, "/v1/casino/bonus/offers/"):
		id := o.id()
		o.offers[id] = parts[4]
		reply(201, map[string]any{"offerId": id})
	case strings.HasSuffix(p, "/register"):
		id := o.id()
		o.brandOf[id] = parts[3]
		o.players[id] = item{"id": id, "brandId": parts[3], "status": "ACTIVE", "email": body["email"]}
		o.order = append(o.order, id)
		reply(201, map[string]any{"playerId": id})
	case strings.HasSuffix(p, "/verify-email"):
		reply(200, map[string]any{"status": "verified"})
	case strings.HasSuffix(p, "/login"):
		reply(200, map[string]any{"token": "jwt-player-secretpart-" + strconv.Itoa(o.n), "refreshToken": "rt-secretpart"})
	case r.Method == http.MethodPatch && strings.HasPrefix(p, "/v1/casino/players/"):
		for k, v := range body {
			o.players[parts[4]][k] = v
		}
		reply(200, map[string]any{})
	case strings.HasPrefix(p, "/internal/v1/wallet/"):
		brand, player := parts[3], parts[4]
		o.signed = append(o.signed, r.Header.Get("X-Internal-Secret") == Signature(internal, brand) && r.Header.Get("X-Internal-Brand-Id") == brand)
		o.add(o.txns, item{"playerId": player, "type": "DEPOSIT", "amount": body["amount"]})
		reply(201, map[string]any{"balance": "100.00"})
	case strings.HasSuffix(p, "/launch"):
		player := body["playerId"].(string)
		o.add(o.sessions, item{"playerId": player, "gameId": body["gameId"]})
		sid := o.order[len(o.order)-1]
		lt := "lt-" + sid
		o.launches[lt] = sid
		reply(200, map[string]any{"sessionId": sid, "launchToken": lt, "gameUrl": "http://fake-st8.svc.cluster.local:30096/game?token=" + lt})
	case p == "/game":
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, "<html>game</html>")
	case p == "/test/bet":
		sid := o.launches[body["launchToken"].(string)]
		player := o.sessions[sid]["playerId"].(string)
		o.add(o.rounds, item{"sessionId": sid, "playerId": player, "provider": body["roundId"]})
		rid := o.order[len(o.order)-1]
		o.add(o.bets, item{"roundId": rid, "playerId": player})
		o.add(o.txns, item{"playerId": player, "type": "BET"})
		reply(200, map[string]any{"status": "ok"})
	case p == "/test/win" || p == "/test/lose":
		sid := o.launches[body["launchToken"].(string)]
		if p == "/test/win" {
			o.add(o.txns, item{"playerId": o.sessions[sid]["playerId"], "type": "WIN"})
		}
		reply(200, map[string]any{"status": "ok"})
	case strings.HasPrefix(p, "/v1/casino/bonus/grant/"):
		if body["type"] == "FREE_SPINS" {
			// The original writes the claim with offer_id "", which the partner API drops.
			reply(200, map[string]any{"grantId": o.id()})
			return
		}
		o.add(o.awards, item{"playerId": parts[5], "offerId": body["offerId"], "value": "50.00"})
		reply(201, map[string]any{"bonusAmount": "50.00"})
	case strings.HasSuffix(p, "/game-catalog-config"):
		if r.Method == http.MethodPut {
			o.disabled = map[string]bool{}
			if m, ok := body["gameOverrides"].(map[string]any); ok {
				for k, v := range m {
					o.disabled[k] = v == false
				}
			}
			reply(200, map[string]any{})
			return
		}
		reply(200, map[string]any{"disabledDevelopers": []any{}, "disabledCategories": []any{}, "gameOverrides": map[string]any{"kept": true}})
	case strings.HasPrefix(p, "/internal/v1/casino/"):
		if r.Header.Get("X-Internal-Secret") != internal {
			reply(401, map[string]any{"error": "unauthorized"})
			return
		}
		if strings.HasSuffix(p, "/player-stats/recompute") {
			snap := o.id()
			o.snapshot[parts[3]] = append(o.snapshot[parts[3]], snap)
			reply(200, map[string]any{"brandId": parts[3], "snapshot": snap})
			return
		}
		reply(200, map[string]any{"brandId": parts[3], "games": 2})
	case p == "/health":
		reply(200, map[string]any{"status": "ok"})
	case p == "/v1/openapi.json":
		reply(200, map[string]any{"openapi": "3.1.0"})
	case p == "/v1/docs":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, "<html>reference</html>")
	case strings.HasPrefix(p, "/v1/"):
		o.partner(w, r, reply, parts[1:], q)
	default:
		reply(404, map[string]any{"error": "not_found"})
	}
}

// partner is the partner API: the token's brand, the quota, then the route.
func (o *stub) partner(w http.ResponseWriter, r *http.Request, reply func(int, any), parts []string, q map[string][]string) {
	auth := r.Header.Get("Authorization")
	if auth == "" {
		reply(401, map[string]any{"error": "jwt_missing"})
		return
	}
	var partner Partner
	for _, p := range Partners {
		if strings.HasPrefix(auth, "Bearer jwt-"+string(p.Auth)+"-") {
			partner = p
		}
	}
	if partner.ClientID == "" {
		reply(401, map[string]any{"error": "jwt_verification_failed"})
		return
	}
	brand := BrandA
	if partner.Letter == "B" {
		brand = BrandB
	}
	if partner.Auth == Quota {
		if now := o.clock.Now(); o.window.IsZero() || now.Sub(o.window) >= time.Minute {
			o.window, o.used = now, 0
		}
		if o.used >= 5 {
			w.Header().Set("Retry-After", "30")
			reply(429, map[string]any{"error": "quota_exceeded"})
			return
		}
		o.used++
	}
	if r.Method != http.MethodGet {
		reply(405, map[string]any{"error": "invalid_request", "message": "Only GET is supported"})
		return
	}
	get := func(k string) string {
		if v := q[k]; len(v) > 0 {
			return v[0]
		}
		return ""
	}
	limit, offset := 100, 0
	if v := get("limit"); v != "" {
		limit, _ = strconv.Atoi(v)
		if limit > 100 {
			reply(400, map[string]any{"error": "limit_exceeded"})
			return
		}
	}
	if v := get("offset"); v != "" {
		offset, _ = strconv.Atoi(v)
	}
	page := func(all []item) {
		total := len(all)
		end := offset + limit
		if offset > total {
			offset = total
		}
		if end > total {
			end = total
		}
		reply(200, map[string]any{"data": all[offset:end], "page": map[string]any{"limit": limit, "offset": offset, "total": total}})
	}
	notFound := func() { reply(404, map[string]any{"error": "not_found"}) }
	visible := func(m map[string]item, id string) (item, bool) {
		rec, ok := m[id]
		if !ok {
			return nil, false
		}
		player, _ := rec["playerId"].(string)
		if rec["brandId"] == brand || o.brandOf[player] == brand {
			return rec, true
		}
		return nil, false
	}
	switch {
	case parts[0] == "contract":
		reply(200, map[string]any{"version": "1.0.0"})
	case parts[0] == "players" && len(parts) == 1:
		page(o.list(o.players, "brandId", brand))
	case parts[0] == "players":
		if !uuidRE.MatchString(parts[1]) {
			reply(400, map[string]any{"error": "invalid_request", "message": "playerId is not a valid identifier"})
			return
		}
		player, ok := visible(o.players, parts[1])
		if !ok {
			notFound()
			return
		}
		if len(parts) == 2 {
			out := item{"id": player["id"], "brandId": brand, "status": "ACTIVE"}
			for _, f := range partner.Fields {
				out[f] = player[f]
			}
			reply(200, out)
			return
		}
		switch parts[2] {
		case "sessions":
			page(o.list(o.sessions, "playerId", parts[1]))
		case "rounds":
			page(o.list(o.rounds, "playerId", parts[1]))
		case "bets":
			page(o.list(o.bets, "playerId", parts[1]))
		case "transactions":
			page(o.list(o.txns, "playerId", parts[1]))
		case "bonus-awards":
			page(o.list(o.awards, "playerId", parts[1]))
		case "stats":
			reply(200, map[string]any{"playerId": parts[1]})
		}
	case len(parts) == 3 && (parts[0] == "sessions" || parts[0] == "rounds"):
		m, child, field := o.sessions, o.rounds, "sessionId"
		if parts[0] == "rounds" {
			m, child, field = o.rounds, o.bets, "roundId"
		}
		if _, ok := visible(m, parts[1]); !ok {
			notFound()
			return
		}
		page(o.list(child, field, parts[1]))
	case len(parts) == 2 && parts[0] != "games":
		m := map[string]map[string]item{"sessions": o.sessions, "rounds": o.rounds, "bets": o.bets,
			"transactions": o.txns, "bonus-awards": o.awards}[parts[0]]
		if rec, ok := visible(m, parts[1]); ok {
			reply(200, rec)
			return
		}
		notFound()
	case parts[0] == "games":
		games := []item{{"id": Game}, {"id": OtherGame}, {"id": "fake-slot-003"}}
		if len(parts) == 1 {
			page(games)
			return
		}
		if o.disabled[parts[1]] {
			notFound()
			return
		}
		reply(200, item{"id": parts[1]})
	case parts[0] == "game-stats":
		if (get("sort") == "stake" || get("sort") == "ggr") && get("currency") == "" {
			reply(400, map[string]any{"error": "invalid_request", "message": "currency is required"})
			return
		}
		reply(200, map[string]any{"data": []any{}, "period": get("period")})
	case parts[0] == "player-stats":
		snaps := o.snapshot[brand]
		if named := get("snapshot"); named != "" {
			for _, sn := range snaps {
				if sn == named {
					reply(200, map[string]any{"data": []any{}, "snapshot": map[string]any{"id": sn}})
					return
				}
			}
			reply(410, map[string]any{"error": "snapshot_expired"})
			return
		}
		reply(200, map[string]any{"data": []any{}, "snapshot": map[string]any{"id": snaps[len(snaps)-1]}})
	default:
		notFound()
	}
}

// strict collects every expectation the stub does not meet: it answers as the original's routes do, so
// a recording's own checks must hold of it.
type strict struct {
	*testing.T
	notes []string
}

func (l *strict) Errorf(format string, args ...any) {
	l.notes = append(l.notes, fmt.Sprintf(format, args...))
}

type run struct {
	seed  File
	recs  map[string]File
	stub  *stub
	notes []string
}

// record sows the seed and makes every recording against one stub, as TestPublicAPI does against the
// original, and reads back what each wrote.
func record(t *testing.T) run {
	t.Helper()
	c := &clock{now: time.Date(2026, 11, 12, 10, 15, 0, 0, time.UTC)}
	o := newStub(c)
	srv := httptest.NewServer(o)
	defer srv.Close()
	dir := t.TempDir()
	issued := 0
	cfg := Config{APIURL: srv.URL, CasinoURL: srv.URL, KeycloakURL: srv.URL, FakeSt8URL: srv.URL, WalletInternalURL: srv.URL,
		InternalSecret: internal, SigningSecret: signing, RecordTo: dir, Commit: "abc1234", Now: c.Now, Sleep: c.Sleep,
		Token: func(name Auth) (string, error) {
			issued++
			return fmt.Sprintf("jwt-%s-secretpart-%d", name, issued), nil
		}}
	l := &strict{T: t}
	s := newSession(l, cfg, SeedName, "016/FR-040")
	seed := s.Sow()
	s.write()
	for _, d := range Scenarios() {
		Scenario(l, cfg, seed, d.Name, d.Requirement, func(s *Session) { d.Run(l, s) })
	}
	read := func(name string) File {
		b, err := os.ReadFile(filepath.Join(dir, Service, FileName(name)+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var f File
		if err := json.Unmarshal(b, &f); err != nil {
			t.Fatal(err)
		}
		return f
	}
	out := run{seed: read(SeedName), recs: map[string]File{}, stub: o, notes: l.notes}
	for _, d := range Scenarios() {
		out.recs[d.Name] = read(d.Name)
	}
	return out
}

func step(t *testing.T, rec File, name string) Stepped {
	t.Helper()
	for _, s := range rec.Steps {
		if s.Name == name {
			return Stepped{s.Request.Path, s.Request.Headers, s.Response.Status, s.Response.Headers}
		}
	}
	t.Fatalf("%s: no step %q", rec.Scenario, name)
	return Stepped{}
}

// Stepped is the part of a recorded step these tests read.
type Stepped struct {
	Path            string
	Headers         map[string]string
	Status          int
	ResponseHeaders map[string]string
}

func TestEveryRecordingHoldsOfAnOriginalThatAnswersAsTheRoutesDo(t *testing.T) {
	r := record(t)
	for _, n := range r.notes {
		t.Error(n)
	}
	for _, ok := range r.stub.signed {
		if !ok {
			t.Error("a deposit was not signed with the internal secret's HMAC of the brand")
		}
	}
	// player-1-of-A's, player-1-of-B's, and player-2-of-A's two (1 + 2 per round reaches 250 with one more).
	if len(r.stub.signed) != 4 {
		t.Errorf("%d deposits", len(r.stub.signed))
	}
	if n := len(r.stub.list(r.stub.txns, "playerId", r.seed.Roles["player-2-of-A"])); n != PagedTransactions {
		t.Errorf("player-2-of-A holds %d transactions", n)
	}
}

// No recording writes a token, a password, a secret or an address as it is, and every token it
// carries is named.
func TestTheSeedAndEveryRecordingWriteNoCredentialAndNameEveryTokenTheyCarry(t *testing.T) {
	r := record(t)
	all := []File{r.seed}
	keys := make([]string, 0, len(r.recs))
	for k := range r.recs {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		all = append(all, r.recs[k])
	}
	for _, rec := range all {
		if rec.Service != Service || rec.Original["commit"] != "abc1234" || len(rec.Steps) == 0 || rec.Requirement != "016/FR-040" {
			t.Errorf("header: %+v", rec.Recording)
		}
		for _, s := range rec.Steps {
			sent := s.Request.Path + string(s.Request.Body) + fmt.Sprint(s.Request.Headers)
			for _, raw := range []string{signing, internal, Signature(internal, BrandA), Signature(internal, BrandB), adminPW, "secretpart"} {
				if strings.Contains(sent, raw) {
					t.Errorf("%s: step %q carries %q: %s", rec.Scenario, s.Name, raw, sent)
				}
			}
			if a, ok := s.Request.Headers["Authorization"]; ok && a != "Bearer not-a-token" && !(strings.HasPrefix(a, "Bearer {") && strings.HasSuffix(a, "}")) {
				t.Errorf("%s: step %q: Authorization %q", rec.Scenario, s.Name, a)
			}
			if strings.Contains(sent, "@example.test") {
				t.Errorf("%s: step %q carries an address as it is: %s", rec.Scenario, s.Name, sent)
			}
		}
	}
	for _, a := range r.stub.auths {
		if a == "Bearer " || strings.HasPrefix(a, "Bearer {") {
			t.Errorf("a request carried %q", a)
		}
	}
}

func TestTheSeedIsSetupOnlyAndNamesEveryRecordByRole(t *testing.T) {
	r := record(t)
	for _, s := range r.seed.Steps {
		if !strings.HasPrefix(s.Name, "setup: ") && !strings.HasPrefix(s.Name, "observe: ") {
			t.Errorf("the seed's step %q would be compared", s.Name)
		}
	}
	roles := r.seed.Roles
	for _, role := range []string{
		"player-1-of-A", "player-2-of-A", "player-3-of-A", "player-1-of-B",
		"session-1-of-A", "session-2-of-A", "session-3-of-A", "session-1-of-B",
		"round-1-of-session-1-of-A", "round-3-of-session-1-of-A", "round-2-of-session-2-of-A", "round-2-of-session-1-of-B",
		"bet-1-of-round-3-of-session-1-of-A", "bet-1-of-round-1-of-session-1-of-B",
		"transaction-1-of-player-1-of-A", "transaction-250-of-player-2-of-A", "transaction-1-of-player-1-of-B",
		"award-1-of-player-1-of-A", "award-1-of-player-1-of-B", "snapshot-1-of-A", "snapshot-1-of-B",
		"game-1", "game-2", "offer-of-A", "offer-of-B",
	} {
		if roles[role] == "" {
			t.Errorf("the seed names no %s", role)
		}
	}
	if roles["transaction-251-of-player-2-of-A"] != "" || roles["award-2-of-player-1-of-A"] != "" || roles["round-4-of-session-1-of-A"] != "" {
		t.Errorf("the seed names records that are not the seed's: %v", roles)
	}
	// Each role is the original's id for it: player-1-of-A's first session's rounds are its rounds.
	if got := r.stub.rounds[roles["round-1-of-session-1-of-A"]]; got == nil || got["sessionId"] != roles["session-1-of-A"] {
		t.Errorf("round-1-of-session-1-of-A: %v", got)
	}
	if got := r.stub.players[roles["player-1-of-A"]]; got["city"] != Profile["city"] {
		t.Errorf("player-1-of-A's profile: %v", got)
	}
	launch := step(t, r.seed, "setup: launch session-2-of-A ("+OtherGame+")")
	if launch.Path != "/v1/casino/sessions/spinvibe/launch" || launch.Headers["Authorization"] != "Bearer {token-player-1-of-A}" {
		t.Errorf("launch: %+v", launch)
	}
	deposit := step(t, r.seed, "setup: deposit-1-of-player-1-of-B")
	if _, ok := deposit.Headers["X-Internal-Secret"]; ok || deposit.Headers["X-Internal-Brand-Id"] != BrandB ||
		deposit.Path != "/internal/v1/wallet/goldvibe/{player-1-of-B}/EUR/deposit" {
		t.Errorf("deposit: %+v", deposit)
	}
	run := step(t, r.seed, "setup: the player statistics run for A")
	if _, ok := run.Headers["X-Internal-Secret"]; ok || run.Path != "/internal/v1/casino/spinvibe/player-stats/recompute" {
		t.Errorf("stats run: %+v", run)
	}
}

func TestEveryRecordingCarriesItsRolesAndTheirIds(t *testing.T) {
	r := record(t)
	for name, rec := range r.recs {
		if rec.Roles["player-1-of-A"] != r.seed.Roles["player-1-of-A"] || len(rec.Roles) < len(r.seed.Roles) {
			t.Errorf("%s: roles %d of the seed's %d", name, len(rec.Roles), len(r.seed.Roles))
		}
		for _, s := range rec.Steps {
			for role, id := range rec.Roles {
				if role != "game-1" && role != "game-2" && role != "game-disabled" && strings.Contains(s.Request.Path, id) {
					t.Errorf("%s: step %q carries %s's id: %s", name, s.Name, role, s.Request.Path)
				}
			}
		}
	}
	walk := r.recs["walk-two-brands"]
	if p := step(t, walk, "a bet").Path; p != "/v1/bets/{bet-1-of-round-1-of-session-1-of-A}" {
		t.Errorf("a bet: %s", p)
	}
	if s := step(t, walk, "the players without a token"); s.Status != 401 || s.Headers != nil {
		t.Errorf("no token: %+v", s)
	}
	if s := step(t, walk, "the players with a token that is not one"); s.Headers["Authorization"] != "Bearer not-a-token" {
		t.Errorf("a token that is not one: %+v", s)
	}
	for _, st := range r.recs["other-brand-ids"].Steps {
		if st.Response.Status != 404 || st.Request.Headers["Authorization"] != "Bearer {fullToken}" || !strings.Contains(st.Request.Path, "-of-B") {
			t.Errorf("other brand: %+v", st)
		}
	}
	if n := len(r.recs["other-brand-ids"].Steps); n != 14 {
		t.Errorf("other brand: %d steps", n)
	}
	paged := r.recs["transactions-paged"]
	if len(paged.Steps) != 4 || paged.Steps[2].Request.Path != "/v1/players/{player-2-of-A}/transactions?limit=100&offset=200" || paged.Steps[3].Response.Status != 400 {
		t.Errorf("paged: %+v", paged.Steps)
	}
	fields := r.recs["identifying-fields"]
	if fields.Steps[0].Request.Headers["Authorization"] != "Bearer {fullToken}" || fields.Steps[1].Request.Headers["Authorization"] != "Bearer {bareToken}" {
		t.Errorf("fields: %+v", fields.Steps)
	}
	if r.recs["games"].Roles["game-disabled"] != "fake-slot-003" || step(t, r.recs["games"], "a disabled game").Path != "/v1/games/{game-disabled}" {
		t.Errorf("games: %v", r.recs["games"].Roles)
	}
	ranking := r.recs["player-ranking"]
	if ranking.Roles["snapshot-2-of-A"] == "" || step(t, ranking, "a retired snapshot").Status != 410 ||
		!strings.HasSuffix(step(t, ranking, "the second page of the first snapshot").Path, "&offset=1&snapshot={snapshot-1-of-A}") {
		t.Errorf("ranking: %+v", ranking.Steps)
	}
	quota := r.recs["quota"]
	if len(quota.Steps) != 6 || quota.Steps[5].Response.Status != 429 || quota.Steps[5].Response.Headers["Retry-After"] == "" ||
		quota.Steps[0].Request.Headers["Authorization"] != "Bearer {quotaToken}" {
		t.Errorf("quota: %+v", quota.Steps)
	}
	if h := step(t, r.recs["contract"], "the reference page").ResponseHeaders; !strings.HasPrefix(h["Content-Type"], "text/html") {
		t.Errorf("the reference page: %v", h)
	}
	if s := step(t, r.recs["contract"], "a POST"); s.Status != 405 {
		t.Errorf("a POST: %+v", s)
	}
}

func TestTheGrantsGiveEachPartnerItsFieldsAndRate(t *testing.T) {
	var v struct {
		CasinoPublicAPI struct {
			Partners []struct {
				ID                string   `json:"id"`
				BrandID           string   `json:"brandId"`
				ScopeLevel        string   `json:"scopeLevel"`
				Enabled           bool     `json:"enabled"`
				IdentifyingFields []string `json:"identifyingFields"`
				DataProcessing    *struct {
					AgreementRef, LawfulBasis string
				} `json:"dataProcessing"`
				Quota struct {
					RequestsPerMinute int `json:"requestsPerMinute"`
				} `json:"quota"`
			} `json:"partners"`
		} `json:"casinoPublicApi"`
	}
	if err := json.Unmarshal(GrantsValues(BrandA, BrandB), &v); err != nil {
		t.Fatal(err)
	}
	got := map[string]string{}
	for _, p := range v.CasinoPublicAPI.Partners {
		agreement := p.DataProcessing != nil && p.DataProcessing.AgreementRef != "" && p.DataProcessing.LawfulBasis != ""
		got[p.ID] = fmt.Sprintf("%s %s %v %d %v %d", p.BrandID, p.ScopeLevel, p.Enabled, len(p.IdentifyingFields), agreement, p.Quota.RequestsPerMinute)
	}
	want := map[string]string{
		"partner-local":                "spinvibe sub_brand true 9 true 600",
		"partner-spinvibe-bare-local":  "spinvibe sub_brand true 0 false 600",
		"partner-goldvibe-local":       "goldvibe sub_brand true 0 false 600",
		"partner-spinvibe-quota-local": "spinvibe sub_brand true 0 false 5",
	}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("grants: %v", got)
	}
}

// The kind realm's partner-local carries spinvibe's scope id as a literal (bundle/helm/values.yaml).
func TestTheScopeIdIsTheOriginalsDerivation(t *testing.T) {
	if got := BrandScopeID("spinvibe"); got != "2867b301-c32d-3879-b766-10321f0302a8" {
		t.Errorf("spinvibe: %s", got)
	}
	rep := ClientRepresentation("partner-x", "goldvibe")
	b, _ := json.Marshal(rep)
	for _, want := range []string{`"claim.value":"partner"`, `"claim.value":"sub_brand"`, `"claim.value":"` + BrandScopeID("goldvibe") + `"`, `"serviceAccountsEnabled":true`} {
		if !strings.Contains(string(b), want) {
			t.Errorf("client: no %s in %s", want, b)
		}
	}
}

// keycloak is Keycloak's admin and token routes, holding the realm's clients.
type keycloak struct {
	mu      sync.Mutex
	clients map[string]string // clientId -> id
	created []map[string]any
}

func (k *keycloak) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	k.mu.Lock()
	defer k.mu.Unlock()
	_ = r.ParseForm()
	reply := func(status int, v any) {
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(v)
	}
	switch {
	case r.URL.Path == "/realms/master/protocol/openid-connect/token":
		if r.PostForm.Get("password") != adminPW {
			reply(401, map[string]any{})
			return
		}
		reply(200, map[string]any{"access_token": "admin-token"})
	case r.Header.Get("Authorization") != "Bearer admin-token" && strings.HasPrefix(r.URL.Path, "/admin/"):
		reply(401, map[string]any{})
	case r.URL.Path == "/admin/realms/eitheror/clients" && r.Method == http.MethodGet:
		if id, ok := k.clients[r.URL.Query().Get("clientId")]; ok {
			reply(200, []map[string]any{{"id": id}})
			return
		}
		reply(200, []map[string]any{})
	case r.URL.Path == "/admin/realms/eitheror/clients" && r.Method == http.MethodPost:
		var rep map[string]any
		_ = json.NewDecoder(r.Body).Decode(&rep)
		k.created = append(k.created, rep)
		k.clients[rep["clientId"].(string)] = "uuid-" + rep["clientId"].(string)
		reply(201, nil)
	case strings.HasSuffix(r.URL.Path, "/client-secret"):
		reply(200, map[string]any{"value": "secret-of-" + strings.TrimPrefix(strings.Split(r.URL.Path, "/")[5], "uuid-")})
	case r.URL.Path == "/realms/eitheror/protocol/openid-connect/token":
		if r.PostForm.Get("client_secret") != "secret-of-"+r.PostForm.Get("client_id") {
			reply(401, map[string]any{})
			return
		}
		reply(200, map[string]any{"access_token": "jwt-of-" + r.PostForm.Get("client_id")})
	default:
		reply(404, map[string]any{})
	}
}

func TestTheRunMakesTheMissingClientsAndFetchesTheirTokens(t *testing.T) {
	k := &keycloak{clients: map[string]string{"partner-local": "uuid-partner-local", "partner-goldvibe-local": "uuid-partner-goldvibe-local"}}
	srv := httptest.NewServer(k)
	defer srv.Close()
	kc := Keycloak{Client: srv.Client(), URL: srv.URL, AdminPassword: adminPW}
	for _, p := range Partners {
		if err := kc.EnsureClient(p, BrandA); err != nil {
			t.Fatal(err)
		}
	}
	if len(k.created) != 2 || k.created[0]["clientId"] != "partner-spinvibe-bare-local" || k.created[1]["clientId"] != "partner-spinvibe-quota-local" {
		t.Errorf("created: %v", k.created)
	}
	for _, p := range Partners {
		if got, err := kc.ClientToken(p.ClientID); err != nil || got != "jwt-of-"+p.ClientID {
			t.Errorf("%s: %q %v", p.ClientID, got, err)
		}
	}
	empty := Keycloak{Client: srv.Client(), URL: srv.URL, AdminPassword: adminPW}
	k.clients = map[string]string{}
	if err := empty.EnsureClient(Partners[0], BrandA); err == nil {
		t.Error("a shipped client the realm lacks was made")
	}
	if _, err := (Keycloak{Client: srv.Client(), URL: "https://auth.example.com", AdminPassword: adminPW}).ClientToken("partner-local"); err == nil {
		t.Error("a Keycloak off this machine was called")
	}
}

// contractRows is every recording of contracts/recording.md's first table: `| n | `name`: … |`.
func contractRows(t *testing.T) []string {
	t.Helper()
	f, err := os.Open(filepath.Join("..", "..", "..", "specs", "016-public-api", "contracts", "recording.md"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	row := regexp.MustCompile("^\\| *[0-9]+ *\\| *`([^`]+)`")
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if m := row.FindStringSubmatch(sc.Text()); m != nil {
			out = append(out, m[1])
		}
	}
	return out
}

func TestEveryRecordingOfTheContractIsDrivenOrSaidWhyNot(t *testing.T) {
	driven := map[string]bool{}
	for _, d := range Scenarios() {
		if driven[d.Name] {
			t.Errorf("two recordings named %q", d.Name)
		}
		driven[d.Name] = true
		if FileName(d.Name) != d.Name {
			t.Errorf("%q is not its own file name", d.Name)
		}
	}
	rows := contractRows(t)
	if len(rows) != 11 {
		t.Errorf("the contract names %d recordings (it says 11)", len(rows))
	}
	known := map[string]bool{}
	for _, n := range rows {
		known[n] = true
		if !driven[n] && NotDriven[n] == "" {
			t.Errorf("%q is neither driven nor said why not", n)
		}
	}
	for n := range driven {
		if !known[n] {
			t.Errorf("the contract names no recording %q", n)
		}
	}
	for n := range Extra {
		if known[n] || driven[n] {
			t.Errorf("%q is an extra and a row", n)
		}
	}
	order := []string{}
	for _, d := range Scenarios() {
		order = append(order, d.Name)
	}
	if strings.Join(order, ",") != strings.Join(rows, ",") {
		t.Errorf("the driver's order %v is not the contract's %v", order, rows)
	}
}

// A players' statistics run the original skips for retention is made again at retainedUntil, within
// the run's patience for it, or fails the recording naming when to run again.
func TestARunSkippedForRetentionWaitsOrSaysWhen(t *testing.T) {
	c := &clock{now: time.Date(2026, 11, 12, 10, 15, 0, 0, time.UTC)}
	until := c.Now().Add(10 * time.Minute)
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Header().Set("Content-Type", "application/json")
		if c.Now().Before(until) {
			fmt.Fprintf(w, `{"brandId":"spinvibe","skipped":"retention","retainedUntil":%q}`, until.Format(time.RFC3339Nano))
			return
		}
		fmt.Fprint(w, `{"brandId":"spinvibe","snapshot":"11111111-1111-4111-8111-111111111111"}`)
	}))
	defer srv.Close()
	for _, tc := range []struct {
		wait  time.Duration
		notes int
	}{{0, 1}, {11 * time.Minute, 0}} {
		l := &strict{T: t}
		s := newSession(l, Config{CasinoURL: srv.URL, InternalSecret: internal, Now: c.Now, Sleep: c.Sleep, RetentionWait: tc.wait}, "r", "016/FR-040")
		s.PlayerStatsRun("A", "snapshot-1-of-A")
		if len(l.notes) != tc.notes || (tc.notes == 0 && (s.Roles()["snapshot-1-of-A"] == "" || len(s.rec.Steps) != 1)) {
			t.Errorf("wait %s: notes %v, roles %v, steps %d", tc.wait, l.notes, s.Roles(), len(s.rec.Steps))
		}
		if tc.notes == 1 && !strings.Contains(l.notes[0], until.Format(time.RFC3339Nano)) {
			t.Errorf("the failure does not say when: %v", l.notes)
		}
	}
	if calls != 3 {
		t.Errorf("%d runs", calls)
	}
}
