package publicapidriver

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"
)

// A recording the original answers otherwise than the contract expects fails in the run's result and
// is still written: the recording is the original's behaviour, and the failure is for the person to
// read before the parity tests are written against it.

// Def is one recording of specs/016-public-api/contracts/recording.md, named as its table names it.
type Def struct {
	Name        string
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Extra are recordings the contract's table does not name: the seed, whose every step is setup.
var Extra = map[string]bool{SeedName: true}

// NotDriven are the contract's recordings the original cannot show, and why. It shows every one.
var NotDriven = map[string]string{}

// Periods and the sorts of the original's rankings (handlers/gameStats.ts, handlers/playerStats.ts).
var (
	Periods     = []string{"1d", "7d", "30d"}
	GameSorts   = []string{"rounds", "sessions", "players", "stake", "ggr"}
	MoneySorts  = map[string]bool{"stake": true, "ggr": true, "deposits": true}
	PlayerSorts = []string{"rounds", "sessions", "stake", "ggr", "deposits"}
)

// get is a compared GET answered `status`.
func get(s *Session, name, path string, auth Auth, status int) Response {
	return s.Want(s.API(name, path, auth), status)
}

// walk is every route for one brand's own records, as scenario 252's walk calls them, by role.
func walk(s *Session, letter string, auth Auth) {
	p := "player-1-of-" + letter
	sess := "session-1-of-" + letter
	round := "round-1-of-" + sess
	get(s, "the players", "/v1/players?limit=100", auth, http.StatusOK)
	get(s, "a player", s.Player(p), auth, http.StatusOK)
	for _, c := range []string{"sessions", "rounds", "bets", "transactions", "bonus-awards"} {
		get(s, "a player's "+c, s.Player(p)+"/"+c+"?limit=100", auth, http.StatusOK)
	}
	get(s, "a session", "/v1/sessions/"+s.Value(sess), auth, http.StatusOK)
	get(s, "a session's rounds", "/v1/sessions/"+s.Value(sess)+"/rounds?limit=100", auth, http.StatusOK)
	get(s, "a round", "/v1/rounds/"+s.Value(round), auth, http.StatusOK)
	get(s, "a round's bets", "/v1/rounds/"+s.Value(round)+"/bets?limit=100", auth, http.StatusOK)
	get(s, "a bet", "/v1/bets/"+s.Value("bet-1-of-"+round), auth, http.StatusOK)
	get(s, "a transaction", "/v1/transactions/"+s.Value("transaction-1-of-"+p), auth, http.StatusOK)
	get(s, "a bonus award", "/v1/bonus-awards/"+s.Value("award-1-of-"+p), auth, http.StatusOK)
	get(s, "the games", "/v1/games?limit=100", auth, http.StatusOK)
	get(s, "a game", "/v1/games/"+s.Value("game-1"), auth, http.StatusOK)
	get(s, "a game's stats", "/v1/games/"+s.Value("game-1")+"/stats", auth, http.StatusOK)
	for _, period := range Periods {
		get(s, "the games ranked by rounds over "+period, "/v1/game-stats?period="+period+"&sort=rounds", auth, http.StatusOK)
	}
}

// byID are the by-id routes and the collections under each parent, for a brand's records.
func byID(s *Session, letter string) []string {
	p := "player-1-of-" + letter
	sess := "session-1-of-" + letter
	round := "round-1-of-" + sess
	paths := []string{s.Player(p)}
	for _, c := range []string{"sessions", "rounds", "bets", "transactions", "bonus-awards", "stats"} {
		paths = append(paths, s.Player(p)+"/"+c)
	}
	return append(paths,
		"/v1/sessions/"+s.Value(sess), "/v1/sessions/"+s.Value(sess)+"/rounds",
		"/v1/rounds/"+s.Value(round), "/v1/rounds/"+s.Value(round)+"/bets",
		"/v1/bets/"+s.Value("bet-1-of-"+round),
		"/v1/transactions/"+s.Value("transaction-1-of-"+p),
		"/v1/bonus-awards/"+s.Value("award-1-of-"+p),
	)
}

// Scenarios is every recording the driver makes after the seed, in the contract's order.
func Scenarios() []Def {
	return []Def{
		{Name: "walk-two-brands", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			// Scenario 252's refusals first: no bearer, and a bearer that is no token.
			s.WantField(get(s, "the players without a token", "/v1/players", NoAuth, http.StatusUnauthorized), "error", "jwt_missing")
			s.Want(s.APIRaw("the players with a token that is not one", "/v1/players", "Bearer not-a-token"), http.StatusUnauthorized)
			walk(s, "A", Full)
			walk(s, "B", Other)
		}},
		{Name: "other-brand-ids", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			for _, path := range byID(s, "B") {
				s.WantField(get(s, "brand B's record under brand A's partner", path, Full, http.StatusNotFound), "error", "not_found")
			}
		}},
		{Name: "transactions-paged", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			for offset := 0; offset < PagedTransactions; offset += 100 {
				r := get(s, fmt.Sprintf("the transactions from %d", offset),
					fmt.Sprintf("%s/transactions?limit=100&offset=%d", s.Player("player-2-of-A"), offset), Full, http.StatusOK)
				if r.Total() != PagedTransactions {
					s.t.Errorf("%s: page.total %d (want %d)", s.rec.Scenario, r.Total(), PagedTransactions)
				}
			}
			s.WantField(get(s, "a page above the limit", s.Player("player-2-of-A")+"/transactions?limit=101", Full,
				http.StatusBadRequest), "error", "limit_exceeded")
		}},
		{Name: "boundaries", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			r := get(s, "an offset past the end", s.Player("player-1-of-A")+"/transactions?limit=100&offset=10000", Full, http.StatusOK)
			if len(r.Data()) != 0 {
				s.t.Errorf("%s: %d records past the end", s.rec.Scenario, len(r.Data()))
			}
			r = get(s, "a player with no bonus awards", s.Player("player-3-of-A")+"/bonus-awards", Full, http.StatusOK)
			if len(r.Data()) != 0 || r.Total() != 0 {
				s.t.Errorf("%s: player-3-of-A's awards: %s", s.rec.Scenario, clip(r.Raw))
			}
			s.WantField(get(s, "a malformed id", "/v1/players/not-a-uuid", Full, http.StatusBadRequest), "error", "invalid_request")
		}},
		{Name: "identifying-fields", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			full := get(s, "the player under the full grant", s.Player("player-1-of-A"), Full, http.StatusOK)
			for _, f := range IdentifyingFields {
				if full.Str(f) == "" {
					s.t.Errorf("%s: the full grant was not served %s", s.rec.Scenario, f)
				}
			}
			bare := get(s, "the player under the empty grant", s.Player("player-1-of-A"), Bare, http.StatusOK)
			for _, f := range IdentifyingFields {
				if _, ok := bare.Body[f]; ok {
					s.t.Errorf("%s: the empty grant was served %s", s.rec.Scenario, f)
				}
			}
		}},
		{Name: "games", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			list := get(s, "the games", "/v1/games?limit=100", Full, http.StatusOK)
			get(s, "a game", "/v1/games/"+s.Value("game-1"), Full, http.StatusOK)
			disabled := ""
			for _, g := range list.Data() {
				if id := str(g, "id"); id != "" && id != Game && id != OtherGame {
					disabled = id
					break
				}
			}
			if disabled == "" {
				s.t.Errorf("%s: no game but the seed's is listed, so none can be disabled", s.rec.Scenario)
				return
			}
			s.Role("game-disabled", disabled)
			get(s, "the game before it is disabled", "/v1/games/"+s.Value("game-disabled"), Full, http.StatusOK)
			s.Disable("A", disabled)
			r := s.Eventually(func() Response { return s.API("a disabled game", "/v1/games/"+s.Value("game-disabled"), Full) },
				func(r Response) bool { return r.Status == http.StatusNotFound })
			s.WantField(s.Want(r, http.StatusNotFound), "error", "not_found")
		}},
		{Name: "game-stats", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			for _, period := range Periods {
				for _, sort := range GameSorts {
					q := "/v1/game-stats?period=" + period + "&sort=" + sort
					if MoneySorts[sort] {
						q += "&currency=" + Currency
					}
					get(s, "the games ranked by "+sort+" over "+period, q, Full, http.StatusOK)
				}
			}
			get(s, "a game's stats", "/v1/games/"+s.Value("game-1")+"/stats", Full, http.StatusOK)
			s.WantField(get(s, "a money ranking without a currency", "/v1/game-stats?period=7d&sort=stake", Full,
				http.StatusBadRequest), "error", "invalid_request")
		}},
		{Name: "player-ranking", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			base := "/v1/player-stats?period=30d&sort=rounds&limit=1"
			first := get(s, "the ranking's first page", base, Full, http.StatusOK)
			if got := first.Snapshot(); got != s.Value("snapshot-1-of-A") {
				s.t.Errorf("%s: the first page is of snapshot %q (want snapshot-1-of-A)", s.rec.Scenario, got)
			}
			s.PlayerStatsRun("A", "snapshot-2-of-A")
			get(s, "the second page of the first snapshot", base+"&offset=1&snapshot="+s.Value("snapshot-1-of-A"), Full, http.StatusOK)
			next := get(s, "the first page again", base, Full, http.StatusOK)
			if got := next.Snapshot(); s.Value("snapshot-2-of-A") != "" && got != s.Value("snapshot-2-of-A") {
				s.t.Errorf("%s: the first page again is of snapshot %q (want snapshot-2-of-A)", s.rec.Scenario, got)
			}
			// A retired snapshot is one no slot holds ready; the original answers a snapshot it never
			// published the same way (playerStatsPointer.ts servableSnapshot), and the retention guard
			// keeps a published one for 30 minutes, so the run names one that never was.
			s.WantField(get(s, "a retired snapshot", base+"&snapshot="+s.UUID("snapshot-retired"), Full, http.StatusGone),
				"error", "snapshot_expired")
			get(s, "the active players ranked", "/v1/player-stats?period=30d&sort=rounds&status=ACTIVE", Full, http.StatusOK)
			for _, sort := range PlayerSorts {
				q := "/v1/player-stats?period=7d&sort=" + sort
				if MoneySorts[sort] {
					q += "&currency=" + Currency
				}
				get(s, "the players ranked by "+sort, q, Full, http.StatusOK)
			}
		}},
		{Name: "player-stats", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			get(s, "a player's stats", s.Player("player-1-of-A")+"/stats", Full, http.StatusOK)
			get(s, "a player with no activity", s.Player("player-3-of-A")+"/stats", Full, http.StatusOK)
			s.WantField(get(s, "another brand's player", s.Player("player-1-of-B")+"/stats", Full, http.StatusNotFound), "error", "not_found")
		}},
		{Name: "quota", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			// The original's window is fixed and opens at a partner's first request in it
			// (quota/partnerQuota.ts): wait out the window the seed's admission check opened.
			if wait := s.quotaFree.Sub(s.cfg.Now()); wait > 0 {
				s.cfg.Sleep(wait)
			}
			for i := 1; i <= 5; i++ {
				get(s, fmt.Sprintf("request %d of 5", i), "/v1/contract", Quota, http.StatusOK)
			}
			r := s.WantField(get(s, "the sixth request", "/v1/contract", Quota, http.StatusTooManyRequests), "error", "quota_exceeded")
			if r.Header.Get("Retry-After") == "" {
				s.t.Errorf("%s: the refusal carries no Retry-After", s.rec.Scenario)
			}
		}},
		{Name: "contract", Requirement: "016/FR-040", Run: func(t testing.TB, s *Session) {
			get(s, "the OpenAPI document", "/v1/openapi.json", NoAuth, http.StatusOK)
			get(s, "the contract's version", "/v1/contract", Full, http.StatusOK)
			r := get(s, "the reference page", "/v1/docs", NoAuth, http.StatusOK)
			if !strings.HasPrefix(r.Header.Get("Content-Type"), "text/html") {
				s.t.Errorf("%s: the reference page is %q", s.rec.Scenario, r.Header.Get("Content-Type"))
			}
			s.WantField(get(s, "health", "/health", NoAuth, http.StatusOK), "status", "ok")
			s.WantField(s.Want(s.APIMethod("a POST", http.MethodPost, "/v1/players", map[string]any{}, Full),
				http.StatusMethodNotAllowed), "error", "invalid_request")
		}},
	}
}

// Disable turns a game off for the brand through the operator's catalogue configuration (BrandConfigRoutes'
// `PUT /v1/casino/brands/{brand}/game-catalog-config`, a per-game override), keeping what the brand had,
// and puts the configuration back after the recording.
func (s *Session) Disable(letter, game string) {
	path := "/v1/casino/brands/" + s.Brand(letter) + "/game-catalog-config"
	was := s.ok(s.Setup("read the brand's catalogue configuration", "casino", http.MethodGet, path, nil, Operator))
	restore := map[string]any{
		"disabledDevelopers": orEmpty(was.Body["disabledDevelopers"]),
		"disabledCategories": orEmpty(was.Body["disabledCategories"]),
		"gameOverrides":      overrides(was.Body["gameOverrides"]),
	}
	changed := map[string]any{}
	for k, v := range restore {
		changed[k] = v
	}
	o := overrides(was.Body["gameOverrides"])
	o[game] = false
	changed["gameOverrides"] = o
	// The original merges `gameOverrides` into what the brand holds, so an override left out is kept:
	// putting back the configuration as it was would leave the game disabled for the next run. The
	// game is put back on by name, unless the brand had its own override for it.
	back := overrides(was.Body["gameOverrides"])
	if _, had := back[game]; !had {
		back[game] = true
	}
	restore["gameOverrides"] = back
	s.ok(s.Setup("disable "+game+" for the brand", "casino", http.MethodPut, path, changed, Operator))
	s.Cleanup(func() {
		s.Setup("put the brand's catalogue configuration back", "casino", http.MethodPut, path, restore, Operator)
	})
}

func orEmpty(v any) []any {
	if a, ok := v.([]any); ok {
		return a
	}
	return []any{}
}

func overrides(v any) map[string]any {
	out := map[string]any{}
	if m, ok := v.(map[string]any); ok {
		for k, b := range m {
			out[k] = b
		}
	}
	return out
}

// quotaWindow is the original's quota window, and a margin.
const quotaWindow = 61 * time.Second
