package reportingdriver

import (
	"net/http"
	"net/url"
	"strings"
	"time"
)

// The original's routes, one helper per route the seed and the recordings use. Amounts are sent as the
// original takes them: a major-unit decimal string (the wallet's deposit, fake-st8's stakes and wins,
// BonusRoutes' `bonusAmount`).

// The run's made-up values, the same on every run, so a replay sends them as they are.
const (
	Password = "Reporting-Driver-1!"
	Currency = "EUR"
	Country  = "GB"
	// Game and OtherGame are fake-st8's catalogue fixtures, one per game session.
	Game      = "book-of-ra"
	OtherGame = "fake-slot-002"
	// Deposit, Stake and BonusAmount are the seed's amounts, in major units.
	Deposit     = "100.00"
	Stake       = "1.00"
	BonusAmount = "10.00"
	// ReportsFlag is the original's Unleash flag behind `/reports` (Flags.BackofficeReportingTab).
	ReportsFlag = "backoffice-reporting-tab"
)

// Round is one of the seed's rounds: its game session (0 or 1) and its win, "" for a loss.
type Round struct {
	Session int
	Win     string
}

// Rounds are the seed's five: three in the first game session, two in the second; three won, two lost.
var Rounds = []Round{{0, "2.50"}, {0, ""}, {0, "0.40"}, {1, ""}, {1, "1.20"}}

// Backoffice is the prefix of every route recorded; Reports and Dashboard its two groups'.
func (s *Session) Backoffice() string { return "/v1/casino/backoffice/" + s.Brand() }
func (s *Session) Reports() string    { return s.Backoffice() + "/reports" }
func (s *Session) Dashboard() string  { return s.Backoffice() + "/dashboard" }

// Dispute is the dispute report's path for `player` with the query `q`, its parameters in the order the
// backoffice sets them (sessionId, fromDate, toDate, format).
func (s *Session) Dispute(player string, q [][2]string) string {
	parts := make([]string, 0, len(q))
	for _, kv := range q {
		parts = append(parts, url.QueryEscape(kv[0])+"="+url.QueryEscape(kv[1]))
	}
	return s.Backoffice() + "/players/" + url.PathEscape(player) + "/dispute-report?" + strings.Join(parts, "&")
}

// Period is the run's thirty days to today, UTC, as `from=…&to=…`.
func (s *Session) Period() string { return "from=" + s.Value("from") + "&to=" + s.Value("to") }

// Seed is what the seed made, named in every recording so a request that carries one carries `{name}`.
type Seed struct {
	Email           string
	PlayerID        string
	PlayerSessionID string    // the login session (a PlayerSession twin's entity id)
	GameSessionIDs  [2]string // the two game sessions launched within it
}

func (seed Seed) name(s *Session) {
	for _, kv := range [][2]string{
		{"email", seed.Email}, {"playerId", seed.PlayerID}, {"playerSessionId", seed.PlayerSessionID},
		{"gameSessionId", seed.GameSessionIDs[0]}, {"otherGameSessionId", seed.GameSessionIDs[1]},
	} {
		if kv[1] != "" {
			s.Remember(kv[0], kv[1])
		}
	}
}

// SeedName is the seed's recording: every step `setup:` or `observe:`, kept so a reader sees what the
// figures rest on; a replay compares nothing in it.
const SeedName = "reporting-seed"

// Sow seeds the brand through the original's own routes: a player registered, verified by the link the
// original would send and signed in (the login session); a deposit through the wallet's internal route,
// as merchant makes one on a captured payment; two game sessions launched within the login session
// and bound at fake-st8; five rounds staked and settled through fake-st8's test hooks, which call the
// ST8 adapter as ST8 does; the operator's bonus with no offer; and the player's own permanent
// exclusion, last, since it ends their play. Each read the recordings rest on is waited for.
func (s *Session) Sow() Seed {
	s.t.Helper()
	var seed Seed
	players := "/v1/casino/players/" + s.Brand()
	r := s.Want(s.Setup("register the player", "casino", http.MethodPost, players+"/register", map[string]any{
		"email": s.Email("email"), "password": Password, "currency": Currency, "country": Country,
		"language": "en", "dateOfBirth": "1990-01-01",
	}, NoAuth), http.StatusCreated)
	seed.Email = s.Value("email")
	if r.Status != http.StatusCreated {
		return seed
	}
	s.Grab("playerId", "$.playerId", r.Str("playerId"))
	seed.PlayerID = s.Value("playerId")
	link := VerificationToken(s.cfg.SigningSecret, s.Brand(), seed.PlayerID, seed.Email, s.cfg.Now().Add(24*time.Hour))
	s.Remember("link", link)
	s.Want(s.Setup("follow the verification link", "casino", http.MethodPost, players+"/verify-email",
		map[string]any{"token": link}, NoAuth), http.StatusOK)
	r = s.Want(s.Setup("sign the player in", "casino", http.MethodPost, players+"/login",
		map[string]any{"email": seed.Email, "password": Password}, NoAuth), http.StatusOK)
	if r.Status != http.StatusOK {
		return seed
	}
	s.Grab("token", "$.token", r.Str("token"))
	s.Remember("refreshToken", r.Str("refreshToken"))

	// The login session is a PlayerSession twin casino-service writes after the sign-in; the backoffice's
	// session list finds it by its player (`playerEmail` matches `player_ref`, the bare player id).
	r = s.Observe("the login session", "casino", s.Backoffice()+"/sessions?type=player&playerEmail="+seed.PlayerID, Operator,
		func(r Response) bool { return len(r.Items("sessions")) > 0 })
	if items := r.Items("sessions"); len(items) > 0 {
		s.Grab("playerSessionId", "$.sessions[0].sessionId", str(items[0], "sessionId"))
		seed.PlayerSessionID = s.Value("playerSessionId")
	}

	s.ok(s.WalletSetup("deposit to the player", http.MethodPost,
		"/internal/v1/wallet/"+s.Brand()+"/"+seed.PlayerID+"/"+Currency+"/deposit",
		map[string]any{"paymentRef": s.UUID("depositRef"), "amount": Deposit, "terminalId": "reporting-driver",
			"pspId": "reporting-driver-psp", "scheme": "card"}))

	launch := [2]string{}
	for i, game := range []string{Game, OtherGame} {
		id, token := []string{"gameSessionId", "otherGameSessionId"}[i], []string{"launchToken", "otherLaunchToken"}[i]
		r = s.Want(s.Setup("launch "+game, "casino", http.MethodPost, "/v1/casino/sessions/"+s.Brand()+"/launch",
			map[string]any{"playerId": seed.PlayerID, "gameId": game, "currency": Currency}, Player), http.StatusOK)
		if r.Status != http.StatusOK {
			continue
		}
		s.Grab(id, "$.sessionId", r.Str("sessionId"))
		s.Grab(token, "$.launchToken", r.Str("launchToken"))
		seed.GameSessionIDs[i], launch[i] = s.Value(id), s.Value(token)
		// fake-st8 builds the game's address from its in-cluster EXTERNAL_URL: its path and query are
		// asked of the fake-st8 this machine reaches, which binds the wallet token through /st8/check.
		page := "/game?token=" + url.QueryEscape(launch[i])
		if u, err := url.Parse(r.Str("gameUrl")); err == nil && u.Path != "" {
			page = u.RequestURI()
		}
		g := s.ok(s.Setup("load "+game+"'s page", "fake-st8", http.MethodGet, page, nil, NoAuth))
		if strings.Contains(strings.ToLower(string(g.Raw)), "session expired") {
			s.t.Errorf("%s: fake-st8 could not bind %s's wallet token (restart fake-st8: the adapter lost its key)", s.rec.Scenario, game)
		}
	}

	settled := [2]int{}
	for i, round := range Rounds {
		lt := launch[round.Session]
		if lt == "" {
			continue
		}
		letter := string(rune('A' + i))
		roundID := s.UUID("round" + letter)
		s.ok(s.Setup("stake round "+letter, "fake-st8", http.MethodPost, "/test/bet",
			map[string]any{"launchToken": lt, "amount": Stake, "currency": Currency, "roundId": roundID}, NoAuth))
		if round.Win != "" {
			s.ok(s.Setup("win round "+letter, "fake-st8", http.MethodPost, "/test/win",
				map[string]any{"launchToken": lt, "roundId": roundID, "amount": round.Win, "currency": Currency}, NoAuth))
		} else {
			s.ok(s.Setup("lose round "+letter, "fake-st8", http.MethodPost, "/test/lose",
				map[string]any{"launchToken": lt, "roundId": roundID, "currency": Currency}, NoAuth))
		}
		settled[round.Session]++
	}
	for i, id := range seed.GameSessionIDs {
		if id == "" {
			continue
		}
		want := settled[i]
		s.Observe("game session "+[]string{"one", "two"}[i]+"'s rounds", "casino",
			s.Backoffice()+"/sessions/game/"+id+"/rounds", Operator,
			func(r Response) bool { return len(r.Items("rounds")) >= want })
	}

	s.ok(s.Setup("the operator's bonus", "casino", http.MethodPost, "/v1/casino/bonus/grant/"+s.Brand()+"/"+seed.PlayerID,
		map[string]any{"bonusAmount": BonusAmount, "wager": "30", "currency": Currency}, Operator))
	s.ok(s.Setup("the player excludes themselves", "casino", http.MethodPut,
		"/v1/casino/rg/"+s.Brand()+"/"+seed.PlayerID+"/permanent-exclusion", nil, Player))
	// The activity feed reads the players and grants the other reads do: once it shows the exclusion and
	// the award, the seed is in every read.
	s.Observe("the seed in the activity feed", "casino", s.Dashboard()+"/activity", Operator, func(r Response) bool {
		seen := map[string]bool{}
		for _, e := range r.Items("events") {
			if str(e, "playerId") == seed.PlayerID {
				seen[str(e, "eventType")] = true
			}
		}
		return seen["self_exclusion"] && seen["bonus_grant"]
	})
	return seed
}

// ReportsOn shows the Reports tab for the brand alone: the flag and the `brandId` context field are
// created in Unleash when missing, the flag given a strategy naming this brand, and enabled; `after`
// takes the clean-ups (the strategy removed, the field removed when made here, the flag disabled again
// when it was off), to run when the whole run ends. With no Unleash token the flag is left as it is.
// Either way the run waits until casino-service answers `/reports` for the brand.
func (s *Session) ReportsOn(after func(func())) {
	if s.cfg.UnleashToken != "" && s.cfg.UnleashURL != "" {
		project := "/api/admin/projects/" + s.cfg.UnleashProject + "/features"
		feature := project + "/" + ReportsFlag
		env := feature + "/environments/" + s.cfg.UnleashEnvironment
		r := s.Unleash("read the Reports flag", http.MethodGet, feature, nil)
		wasOn := false
		if r.Status == http.StatusNotFound {
			s.ok(s.Unleash("create the Reports flag", http.MethodPost, project, map[string]any{"name": ReportsFlag, "type": "release"}))
		} else if envs, ok := r.Body["environments"].([]any); ok {
			for _, e := range envs {
				if m, ok := e.(map[string]any); ok && m["name"] == s.cfg.UnleashEnvironment {
					wasOn, _ = m["enabled"].(bool)
				}
			}
		}
		if c := s.Unleash("read the brand's context field", http.MethodGet, "/api/admin/context/brandId", nil); c.Status == http.StatusNotFound {
			s.ok(s.Unleash("define the brand's context field", http.MethodPost, "/api/admin/context", map[string]any{"name": "brandId"}))
			after(func() {
				s.Unleash("remove the brand's context field", http.MethodDelete, "/api/admin/context/brandId", nil)
			})
		}
		strategy := s.ok(s.Unleash("show the Reports tab for the brand", http.MethodPost, env+"/strategies", map[string]any{
			"name": "flexibleRollout",
			"constraints": []map[string]any{
				{"contextName": "brandId", "operator": "IN", "values": []string{s.Brand()}},
			},
			"parameters": map[string]string{"rollout": "100", "stickiness": "default", "groupId": ReportsFlag},
		}))
		if id := strategy.Str("id"); id != "" {
			s.Remember("strategyId", id)
			after(func() { s.Unleash("remove the brand's strategy", http.MethodDelete, env+"/strategies/"+id, nil) })
		}
		s.ok(s.Unleash("enable the Reports flag", http.MethodPost, env+"/on", nil))
		if !wasOn {
			after(func() { s.Unleash("disable the Reports flag", http.MethodPost, env+"/off", nil) })
		}
	}
	s.Observe("the Reports tab shown for the brand", "casino", s.Reports()+"/ggr?"+s.Period(), Operator,
		func(r Response) bool { return r.Status != http.StatusNotFound })
}
