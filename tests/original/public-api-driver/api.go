package publicapidriver

import (
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

// The seed: activity with known figures on both brands, made through the original's own routes, and
// every record it made named by role. Amounts are sent as the original takes them: major-unit decimal
// strings (the wallet's deposit, fake-st8's stakes and wins, the offer and the grant).

// The run's made-up values, the same on every run, so a replay sends them as they are.
const (
	Password = "Public-Api-Driver-1!"
	Currency = "EUR"
	Country  = "GB"
	// Game and OtherGame are fake-st8's catalogue fixtures; the roles `game-1` and `game-2`.
	Game      = "book-of-ra"
	OtherGame = "fake-slot-002"
	Deposit   = "100.00" // each deposit
	Stake     = "1.00"   // each round's stake
	// AwardDeposit is the deposit an operator's grant of the brand's deposit-match offer is priced on:
	// 100 per cent of it, so the award is worth 50.00.
	AwardDeposit = "50.00"
	// FreeSpins are the operator's free rounds with no offer on player-1-of-A, at FreeSpinValue each.
	FreeSpins     = 10
	FreeSpinValue = "0.10"
	// PagedTransactions is how many transactions player-2-of-A holds (the transactions-paged recording).
	PagedTransactions = 250
	// SeedName is the seed's recording: every step `setup:` or `observe:`, kept so a reader sees what
	// the figures rest on; a replay compares nothing in it.
	SeedName = "public-api-seed"
)

// Profile is player-1-of-A's every identifying field, set through the player's own profile edit.
var Profile = map[string]string{
	"firstName": "Ada", "lastName": "Lovelace", "dateOfBirth": "1990-01-01", "phone": "+447700900123",
	"addressLine1": "1 Analytical Row", "city": "London", "stateProv": "Greater London", "postalCode": "N1 9GU",
}

// Activity is one player's seeded play: the game sessions in order, each its game and its rounds' wins
// ("" a loss), every round staked Stake.
type Activity struct {
	Player   string // the role, e.g. "player-1-of-A"
	Letter   string // the brand
	Profile  bool   // every identifying field set
	Deposits int
	Sessions []PlayedSession
	Award    bool // the operator's grant of the brand's offer
	Spins    bool // the operator's free rounds with no offer
}

// PlayedSession is one game session of the seed.
type PlayedSession struct {
	Game   string
	Rounds []string
}

// Plan is the seed's activity, in the order it is made. Sessions are numbered across their brand in
// this order (`session-1-of-A`, `session-2-of-A` are player-1-of-A's, `session-3-of-A` is
// player-2-of-A's). player-2-of-A's rounds are added by FillTransactions until it holds
// PagedTransactions; player-3-of-A plays nothing (a player with no statistics and no awards).
var Plan = []Activity{
	{Player: "player-1-of-A", Letter: "A", Profile: true, Deposits: 1, Award: true, Spins: true, Sessions: []PlayedSession{
		{Game, []string{"2.50", "", "0.40"}},
		{OtherGame, []string{"", "1.20"}},
	}},
	{Player: "player-2-of-A", Letter: "A", Sessions: []PlayedSession{{Game, nil}}},
	{Player: "player-3-of-A", Letter: "A"},
	{Player: "player-1-of-B", Letter: "B", Deposits: 1, Award: true, Sessions: []PlayedSession{
		{Game, []string{"2.00", ""}},
	}},
}

// Seed is what the seed made: every role and the original's id for it, and when the quota client was
// last used (its window is a minute from its first request).
type Seed struct {
	Roles     map[string]string
	QuotaUsed time.Time
}

func (seed Seed) name(s *Session) {
	keys := make([]string, 0, len(seed.Roles))
	for k := range seed.Roles {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		s.Role(k, seed.Roles[k])
	}
	if !seed.QuotaUsed.IsZero() {
		s.quotaFree = seed.QuotaUsed.Add(quotaWindow)
	}
}

// API paths, built from roles so a request carries `{role}`.
func (s *Session) Player(role string) string { return "/v1/players/" + url.PathEscape(s.Value(role)) }

// Players is the casino-service player routes of a brand.
func (s *Session) Players(letter string) string { return "/v1/casino/players/" + s.Brand(letter) }

// PlayerToken is the Auth a player's sign-in is remembered under.
func PlayerToken(role string) Auth { return Auth("token-" + role) }

// Sow makes the seed: the grants loaded and the four partners admitted, the brands' offers, every
// player of the Plan with their play, player-2-of-A's transactions, the statistics runs, and the roles
// of every record the recordings read, found through the partner API.
func (s *Session) Sow() Seed {
	s.t.Helper()
	s.Role("game-1", Game)
	s.Role("game-2", OtherGame)
	quotaUsed := s.Admitted()
	for _, letter := range []string{"A", "B"} {
		s.Offer(letter)
	}
	sessions := map[string]int{}
	for _, a := range Plan {
		s.Register(a.Player, a.Letter, a.Profile)
		if s.Value(a.Player) == "" {
			continue
		}
		for i := 0; i < a.Deposits; i++ {
			s.Deposit(a.Player, a.Letter, fmt.Sprintf("deposit-%d-of-%s", i+1, a.Player))
		}
		for _, played := range a.Sessions {
			sessions[a.Letter]++
			name := fmt.Sprintf("session-%d-of-%s", sessions[a.Letter], a.Letter)
			launch := s.Launch(a.Player, a.Letter, played.Game, name)
			for k, win := range played.Rounds {
				s.Round(launch, fmt.Sprintf("provider-round-%d-of-%s", k+1, name), win)
			}
			if a.Player == "player-2-of-A" {
				s.FillTransactions(a.Player, a.Letter, launch, PagedTransactions)
			}
		}
		if a.Award {
			s.Award(a.Player, a.Letter)
		}
		if a.Spins {
			s.Spins(a.Player, a.Letter)
		}
	}
	s.Discover()
	s.StatsRuns()
	return Seed{Roles: s.Roles(), QuotaUsed: quotaUsed}
}

// Admitted waits until every partner is answered 200 on `/v1/contract`: its client is in the realm,
// its grant in the original's grants file, and the file re-read. Returns when the quota client's
// window opened.
func (s *Session) Admitted() time.Time {
	var quotaUsed time.Time
	for _, p := range Partners {
		s.observeWithin(s.cfg.GrantPatience, "the "+p.ClientID+" grant in force", Service, "/v1/contract", p.Auth,
			func(r Response) bool { return r.Status == http.StatusOK })
		if p.Auth == Quota {
			quotaUsed = s.cfg.Now()
		}
	}
	return quotaUsed
}

// Offer creates the brand's deposit-match offer through the operator's offer route (BonusRoutes'
// `POST /offers/{brand}`, active on creation), named `offer-of-<letter>`.
func (s *Session) Offer(letter string) {
	now := s.cfg.Now().UTC()
	r := s.ok(s.Setup("the offer of "+letter, "casino", http.MethodPost, "/v1/casino/bonus/offers/"+s.Brand(letter), map[string]any{
		"name": "public-api driver " + letter, "description": "public-api driver", "type": "DEPOSIT_MATCH", "currency": Currency,
		"valid_from": now.Add(-time.Hour).Format(time.RFC3339), "valid_until": now.Add(7 * 24 * time.Hour).Format(time.RFC3339),
		"bonus_percentage": "100", "max_bonus_value": "500.00", "min_deposit_amount": "10.00", "max_convertible_amount": "500.00",
		"wagering_multiplier": "1", "wagering_window_days": "30", "max_claims_per_player": "1", "claim_window_days": "30",
		"priority_order": "1",
	}, Operator))
	if id := r.Str("offerId"); id != "" {
		// A bonus award's `offerId` is this id, so it is a role.
		s.Grab("offer-of-"+letter, "$.offerId", id)
		s.roles["offer-of-"+letter] = id
	}
}

// Register registers a player of the brand, follows the verification link the original would send,
// signs them in (remembered as `token-<role>`) and, with profile, sets every identifying field.
func (s *Session) Register(role, letter string, profile bool) {
	brand := s.Brand(letter)
	email := s.Email("email-of-" + role)
	r := s.Want(s.Setup("register "+role, "casino", http.MethodPost, s.Players(letter)+"/register", map[string]any{
		"email": email, "password": Password, "currency": Currency, "country": Country,
		"language": "en", "dateOfBirth": Profile["dateOfBirth"],
	}, NoAuth), http.StatusCreated)
	if r.Status != http.StatusCreated {
		return
	}
	s.Grab(role, "$.playerId", r.Str("playerId"))
	s.roles[role] = s.Value(role)
	link := VerificationToken(s.cfg.SigningSecret, brand, s.Value(role), email, s.cfg.Now().Add(24*time.Hour))
	s.Remember("link-of-"+role, link)
	s.Want(s.Setup("follow "+role+"'s verification link", "casino", http.MethodPost, s.Players(letter)+"/verify-email",
		map[string]any{"token": link}, NoAuth), http.StatusOK)
	r = s.Want(s.Setup("sign "+role+" in", "casino", http.MethodPost, s.Players(letter)+"/login",
		map[string]any{"email": email, "password": Password}, NoAuth), http.StatusOK)
	if r.Status != http.StatusOK {
		return
	}
	s.Grab(string(PlayerToken(role)), "$.token", r.Str("token"))
	s.Remember("refresh-of-"+role, r.Str("refreshToken"))
	if profile {
		body := map[string]any{}
		for k, v := range Profile {
			body[k] = v
		}
		s.ok(s.Setup(role+" completes their profile", "casino", http.MethodPatch,
			s.Players(letter)+"/"+url.PathEscape(s.Value(role)), body, PlayerToken(role)))
	}
}

// Deposit credits Deposit to the player through the wallet's internal route, as merchant-service does
// on a captured payment.
func (s *Session) Deposit(role, letter, ref string) {
	brand := s.Brand(letter)
	s.ok(s.WalletSetup(ref, brand, http.MethodPost,
		"/internal/v1/wallet/"+brand+"/"+s.Value(role)+"/"+Currency+"/deposit",
		map[string]any{"paymentRef": s.UUID(ref), "amount": Deposit, "terminalId": "public-api-driver",
			"pspId": "public-api-driver-psp", "scheme": "card"}))
}

// Launch launches a game session for the player and binds it at fake-st8 (its game page binds the
// wallet token through /st8/check); returns the launch token, remembered as `launch-<session>`.
func (s *Session) Launch(role, letter, game, session string) string {
	r := s.Want(s.Setup("launch "+session+" ("+game+")", "casino", http.MethodPost, "/v1/casino/sessions/"+s.Brand(letter)+"/launch",
		map[string]any{"playerId": s.Value(role), "gameId": game, "currency": Currency}, PlayerToken(role)), http.StatusOK)
	if r.Status != http.StatusOK {
		return ""
	}
	s.Grab("launch-"+session, "$.launchToken", r.Str("launchToken"))
	if id := r.Str("sessionId"); id != "" {
		s.Grab("casino-"+session, "$.sessionId", id)
	}
	page := "/game?token=" + url.QueryEscape(s.Value("launch-"+session))
	if u, err := url.Parse(r.Str("gameUrl")); err == nil && u.Path != "" {
		page = u.RequestURI()
	}
	g := s.ok(s.Setup("load "+session+"'s game page", "fake-st8", http.MethodGet, page, nil, NoAuth))
	if strings.Contains(strings.ToLower(string(g.Raw)), "session expired") {
		s.t.Errorf("%s: fake-st8 could not bind %s's wallet token (restart fake-st8: the adapter lost its key)", s.rec.Scenario, session)
	}
	return s.Value("launch-" + session)
}

// Round stakes one round through fake-st8's test hooks, which call the ST8 adapter as ST8 does, and
// settles it: won for win, lost when win is "".
func (s *Session) Round(launch, name, win string) {
	if launch == "" {
		return
	}
	id := s.UUID(name)
	s.ok(s.Setup("stake "+name, "fake-st8", http.MethodPost, "/test/bet",
		map[string]any{"launchToken": launch, "amount": Stake, "currency": Currency, "roundId": id}, NoAuth))
	if win != "" {
		s.ok(s.Setup("win "+name, "fake-st8", http.MethodPost, "/test/win",
			map[string]any{"launchToken": launch, "roundId": id, "amount": win, "currency": Currency}, NoAuth))
	} else {
		s.ok(s.Setup("lose "+name, "fake-st8", http.MethodPost, "/test/lose",
			map[string]any{"launchToken": launch, "roundId": id, "currency": Currency}, NoAuth))
	}
}

// Award grants the player the brand's offer as the operator (BonusRoutes' `POST /grant/{brand}/{player}`
// with `offerId`, `Mode.OperatorOverride`), which writes the `bonus-claims` twin the partner API serves.
func (s *Session) Award(role, letter string) {
	offer := s.Value("offer-of-" + letter)
	if offer == "" {
		s.t.Errorf("%s: no offer of %s to award", s.rec.Scenario, letter)
		return
	}
	s.ok(s.Setup("award "+role+" the offer of "+letter, "casino", http.MethodPost,
		"/v1/casino/bonus/grant/"+s.Brand(letter)+"/"+s.Value(role),
		map[string]any{"offerId": offer, "depositAmount": AwardDeposit}, Operator))
}

// Spins grants the player free rounds with no offer as the operator (the grant route's shape B,
// `type: FREE_SPINS`). The original writes their `bonus-claims` twin with `face_value` 0 and `offer_id`
// "" (BonusRoutes.grantFreeSpins), so the walk records whether the partner API lists it.
func (s *Session) Spins(role, letter string) {
	s.ok(s.Setup("give "+role+" free rounds with no offer", "casino", http.MethodPost,
		"/v1/casino/bonus/grant/"+s.Brand(letter)+"/"+s.Value(role),
		map[string]any{"type": "FREE_SPINS", "gameId": Game, "provider": "ST8", "spinCount": FreeSpins,
			"spinValue": FreeSpinValue, "winningsDestination": "real_balance", "freeSpinsExpiryDays": 7}, Operator))
}

// transactions is a player's transaction count as the partner API pages it.
func (s *Session) transactions(role string, auth Auth) string {
	return s.Player(role) + "/transactions?limit=1"
}

// Settled reads a page until its total is above `above` and the same on two reads two seconds apart:
// the wallet writes a round's transactions one movement at a time.
func (s *Session) Settled(name, path string, auth Auth, above int) int {
	last := -2
	r := s.observeWithin(3*s.cfg.Patience, name, Service, path, auth, func(r Response) bool {
		t := r.Total()
		held := t > above && t == last
		last = t
		if !held {
			s.cfg.Sleep(2 * time.Second)
		}
		return held
	})
	return r.Total()
}

// FillTransactions brings the player to `want` transactions: one deposit and one won round are made and
// counted, then as many more of each as reach `want` exactly (the original's count of transactions per
// deposit and per round is read, not assumed), and the total is waited for.
func (s *Session) FillTransactions(role, letter, launch string, want int) {
	path := s.transactions(role, Full)
	s.Deposit(role, letter, "deposit-1-of-"+role)
	afterDeposit := s.Settled(role+"'s transactions after a deposit", path, Full, 0)
	s.Round(launch, "provider-round-1-of-"+role, Stake)
	afterRound := s.Settled(role+"'s transactions after a round", path, Full, afterDeposit)
	perDeposit, perRound := afterDeposit, afterRound-afterDeposit
	if perDeposit <= 0 || perRound <= 0 {
		s.t.Errorf("%s: a deposit made %d transactions and a round %d", s.rec.Scenario, perDeposit, perRound)
		return
	}
	rest := want - afterRound
	deposits := -1
	for d := 0; d <= perRound && d*perDeposit <= rest; d++ {
		if (rest-d*perDeposit)%perRound == 0 {
			deposits = d
			break
		}
	}
	if deposits < 0 {
		s.t.Errorf("%s: %d transactions cannot be reached in deposits of %d and rounds of %d", s.rec.Scenario, want, perDeposit, perRound)
		return
	}
	for d := 0; d < deposits; d++ {
		s.Deposit(role, letter, fmt.Sprintf("deposit-%d-of-%s", d+2, role))
	}
	for n := 0; n < (rest-deposits*perDeposit)/perRound; n++ {
		s.Round(launch, fmt.Sprintf("provider-round-%d-of-%s", n+2, role), Stake)
	}
	s.observeWithin(3*s.cfg.Patience, role+"'s transactions", Service, path, Full, func(r Response) bool { return r.Total() == want })
}

// Discover names every record the recordings read, found through the partner API in its own order
// (sessions by `started_at`, rounds, bets and transactions by `created_at`, awards by `granted_at`):
// `session-<n>-of-<brand>`, `round-<k>-of-<session>`, `bet-<k>-of-<round>`,
// `transaction-<k>-of-<player>`, `award-<k>-of-<player>`.
func (s *Session) Discover() {
	sessions := map[string]int{}
	for _, a := range Plan {
		if s.Value(a.Player) == "" {
			continue
		}
		auth := Full
		if a.Letter == "B" {
			auth = Other
		}
		want := len(a.Sessions)
		listed := s.Observe(a.Player+"'s game sessions", Service, s.Player(a.Player)+"/sessions?limit=100", auth,
			func(r Response) bool { return r.Status == http.StatusOK && len(r.Data()) >= want })
		for i, rec := range listed.Data() {
			if i >= want {
				break
			}
			sessions[a.Letter]++
			session := fmt.Sprintf("session-%d-of-%s", sessions[a.Letter], a.Letter)
			s.Role(session, str(rec, "id"))
			if a.Player == "player-2-of-A" {
				continue // its rounds are the transactions' filler; none is read by id
			}
			n := len(a.Sessions[i].Rounds)
			rounds := s.Observe(session+"'s rounds", Service, "/v1/sessions/"+s.Value(session)+"/rounds?limit=100", auth,
				func(r Response) bool { return len(r.Data()) >= n })
			for k, round := range rounds.Data() {
				name := fmt.Sprintf("round-%d-of-%s", k+1, session)
				s.Role(name, str(round, "id"))
				bets := s.Observe(name+"'s bets", Service, "/v1/rounds/"+s.Value(name)+"/bets?limit=100", auth,
					func(r Response) bool { return len(r.Data()) >= 1 })
				for j, bet := range bets.Data() {
					s.Role(fmt.Sprintf("bet-%d-of-%s", j+1, name), str(bet, "id"))
				}
			}
		}
		for offset := 0; ; offset += 100 {
			page := s.Observe(fmt.Sprintf("%s's transactions from %d", a.Player, offset), Service,
				fmt.Sprintf("%s/transactions?limit=100&offset=%d", s.Player(a.Player), offset), auth,
				func(r Response) bool { return r.Status == http.StatusOK })
			for k, t := range page.Data() {
				s.Role(fmt.Sprintf("transaction-%d-of-%s", offset+k+1, a.Player), str(t, "id"))
			}
			if len(page.Data()) < 100 {
				break
			}
		}
		if a.Award {
			awards := s.Observe(a.Player+"'s bonus awards", Service, s.Player(a.Player)+"/bonus-awards?limit=100", auth,
				func(r Response) bool { return len(r.Data()) >= 1 })
			for k, award := range awards.Data() {
				s.Role(fmt.Sprintf("award-%d-of-%s", k+1, a.Player), str(award, "id"))
			}
		}
	}
}

// StatsRuns runs the original's two hourly statistics jobs for both brands, through casino-service's
// internal routes the CronJobs call, after the next full hour when the run waits for it. The players'
// run names its snapshot `snapshot-1-of-<brand>`.
func (s *Session) StatsRuns() {
	if s.cfg.StatsWait {
		next := s.cfg.Now().UTC().Truncate(time.Hour).Add(time.Hour + 2*time.Minute)
		s.cfg.Sleep(next.Sub(s.cfg.Now()))
	}
	for _, letter := range []string{"A", "B"} {
		s.ok(s.InternalSetup("the game statistics run for "+letter, "/internal/v1/casino/"+s.Brand(letter)+"/game-stats/recompute"))
		s.PlayerStatsRun(letter, "snapshot-1-of-"+letter)
	}
}

// PlayerStatsRun runs the players' statistics for the brand and names the snapshot it publishes. A run
// the original skips for retention is made again at `retainedUntil` when the run may wait that long;
// only the last answer is kept.
func (s *Session) PlayerStatsRun(letter, role string) {
	path := "/internal/v1/casino/" + s.Brand(letter) + "/player-stats/recompute"
	for {
		r := s.InternalSetup("the player statistics run for "+letter, path)
		if r.Str("skipped") == "" {
			s.ok(r)
			if r.Status == http.StatusOK {
				s.Grab(role, "$.snapshot", r.Str("snapshot"))
				s.roles[role] = s.Value(role)
			}
			return
		}
		until, err := time.Parse(time.RFC3339Nano, r.Str("retainedUntil"))
		wait := until.Sub(s.cfg.Now()) + 5*time.Second
		if err != nil || wait > s.cfg.RetentionWait {
			s.t.Errorf("%s: the original skipped the player statistics run for %s until %s (retention); run again then, or give -retention-wait",
				s.rec.Scenario, s.Brand(letter), r.Str("retainedUntil"))
			return
		}
		s.rec.Steps = s.rec.Steps[:len(s.rec.Steps)-1]
		s.cfg.Sleep(wait)
	}
}
