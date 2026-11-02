package rewardsdriver

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// The original's reward API, one helper per route the scenarios use. Amounts are given in minor units
// and sent as the original takes them: a major-unit decimal string (BonusRoutes reads `bonusAmount`,
// `spinValue` and an offer's money fields as BigDecimals of major units).

// The run's made-up values, the same on every run, so a replay sends them as they are.
const (
	Password = "Rewards-Driver-1!"
	Currency = "EUR"
	// Game is fake-st8's free-rounds-eligible catalogue fixture (the FreeSpinsPlay tests' fsGameId);
	// NoGame is a game no catalogue holds.
	Game     = "book-of-ra"
	GameName = "Book of Ra"
	NoGame   = "rewards-driver-no-such-game"
	// WebhookKey is the made-up Customer.io signing key the driver writes to the brand's legacy CRM
	// config, as scenario 21 does (s21SeedCrmSigningKey); not a credential of any system.
	WebhookKey = "rewards-driver-webhook-key"
	// KillSwitch is the original's Unleash flag that turns duplicate detection off for a brand
	// (Flags.DisableMultiAccountDetection).
	KillSwitch = "casino-disable-multi-account-detection"
)

// Players is the registration and sign-in routes' prefix; Bonus the player's and operator's reward
// routes' (BonusRoutes); Backoffice the operator's reward lists'.
func (s *Session) Players() string    { return "/v1/casino/players/" + s.Brand() }
func (s *Session) Bonus() string      { return "/v1/casino/bonus" }
func (s *Session) Backoffice() string { return "/v1/casino/backoffice/" + s.Brand() }

// ok checks a setup write the original answers 2xx.
func (s *Session) ok(r Response) Response {
	if r.Status < 200 || r.Status > 299 {
		s.t.Errorf("%s: a setup step was answered %d: %s", s.rec.Scenario, r.Status, string(r.Raw))
	}
	return r
}

// Active is the scenario's player registered (with no names, so the original computes no identity
// hash and a run's players never match each other), verified by the link the original would send and
// signed in, all as setup; then the duplicate check the registration started is waited out, so a
// scenario's own flags are not overwritten by the detector's.
func (s *Session) Active() {
	s.t.Helper()
	body := map[string]any{
		"email": s.Email("email"), "password": Password, "currency": Currency,
		"country": "GB", "language": "en", "dateOfBirth": "1990-01-01",
	}
	r := s.Want(s.Setup("register the player", "casino", http.MethodPost, s.Players()+"/register", body, NoAuth), http.StatusCreated)
	if r.Status != http.StatusCreated {
		return
	}
	s.Grab("playerId", "$.playerId", r.Str("playerId"))
	link := VerificationToken(s.cfg.SigningSecret, s.Brand(), s.Value("playerId"), s.Value("email"), s.cfg.Now().Add(24*time.Hour))
	s.Remember("link", link)
	s.Want(s.Setup("follow the player's verification link", "casino", http.MethodPost, s.Players()+"/verify-email",
		map[string]any{"token": link}, NoAuth), http.StatusOK)
	r = s.Want(s.Setup("sign the player in", "casino", http.MethodPost, s.Players()+"/login",
		map[string]any{"email": s.Value("email"), "password": Password}, NoAuth), http.StatusOK)
	if r.Status == http.StatusOK {
		s.Grab("token", "$.token", r.Str("token"))
		s.Remember("refreshToken", r.Str("refreshToken"))
	}
	s.Observe("the player's duplicate check has ended", "substrate", http.MethodGet, s.PlayerTwin(), NoAuth,
		func(r Response) bool {
			return r.Status == http.StatusOK && r.Prop("pending_duplicate_check") == "false"
		})
}

// PlayerTwin is the player's twin on substrate, where casino-service keeps the flags the evaluator reads.
func (s *Session) PlayerTwin() string {
	return "/twins/casino-" + s.Brand() + "/" + s.Value("playerId")
}

// Flags writes the player's duplicate-check flags where MultiAccountDetector and the registration write
// them: `pending_duplicate_check`, `no_deposit_offer_blocked` and `suspected_duplicate` ("true" or
// "false"), with `registered_at` set `since` ago when `since` is not zero. Every flag is written, so a
// scenario does not depend on what the detector found.
func (s *Session) Flags(what string, pending, blocked, duplicate bool, since time.Duration) {
	props := map[string]any{
		"pending_duplicate_check":  boolText(pending),
		"no_deposit_offer_blocked": boolText(blocked),
		"suspected_duplicate":      boolText(duplicate),
	}
	if since != 0 {
		at := s.cfg.Now().Add(-since).UTC().Format(time.RFC3339)
		s.Remember("registeredAt", at)
		props["registered_at"] = at
	}
	s.ok(s.Setup(what, "substrate", http.MethodPost, s.PlayerTwin(), props, NoAuth))
}

func boolText(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

// Offer writes a deposit match offer under a fresh UUID `name` carrying the code minted under `code`
// ("" for none), as scenario 21's s21CreateOffer writes one (every rule's field set to pass), with
// `overrides` applied, and marks it active; it is deactivated after the scenario. Returns its id.
func (s *Session) Offer(name, code string, overrides map[string]string) string {
	id := s.UUID(name)
	now := s.cfg.Now().UTC()
	from, until := now.Add(-time.Hour).Format(time.RFC3339), now.Add(24*time.Hour).Format(time.RFC3339)
	s.Remember(name+"ValidFrom", from)
	s.Remember(name+"ValidUntil", until)
	props := map[string]string{
		"offer_id": id, "brand_id": s.Brand(), "name": "rewards driver " + name, "type": "DEPOSIT_MATCH",
		"payload_type": "bonus_funds", "is_active": "true", "currency": Currency, "valid_from": from, "valid_until": until,
		"min_deposit_amount": "10.00", "bonus_percentage": "100", "max_bonus_value": "500.00",
		"max_convertible_amount": "500.00", "wagering_multiplier": "1", "wagering_window_days": "30",
		"priority_order": "1", "max_claims_per_player": "1", "claim_window_days": "30", "excluded_offer_ids": "",
		"redemption_codes": "", "deposit_number_target": "ANY", "max_concurrent_grants": "1", "conflicts_with": "",
		"no_deposit_required": "false",
	}
	if code != "" {
		// ":0" is the original's stored form (BonusRoutes' enter-code matches `code + ":"`).
		props["redemption_codes"] = s.Code(code) + ":0"
	}
	for k, v := range overrides {
		props[k] = v
	}
	s.ok(s.Setup("the offer "+name, "substrate", http.MethodPost, "/twins/casino-bonus-offers/"+id, props, NoAuth))
	s.ok(s.Setup("the offer "+name+" is active", "substrate", http.MethodPost, "/twins/casino-bonus-offers/"+id+"/concept",
		map[string]any{"conceptId": "bonus.offer.active"}, NoAuth))
	s.Cleanup(func() {
		s.Setup("deactivate the offer "+name, "substrate", http.MethodPost, "/twins/casino-bonus-offers/"+id,
			map[string]any{"is_active": "false"}, NoAuth)
	})
	return id
}

// NoDeposit is the overrides of an offer with no deposit required.
var NoDeposit = map[string]string{"no_deposit_required": "true"}

// EnterCode enters the code minted under `code` as the player, again until the original's projection
// shows the offer (a fresh offer is answered `invalid_code` for a moment, as scenario 16's s16EnterCode
// allows); only the last answer is kept.
func (s *Session) EnterCode(name, code string) Response {
	return s.Eventually(func() Response {
		return s.Casino(name, http.MethodPost, s.Bonus()+"/"+s.Brand()+"/enter-code", map[string]any{"code": s.Value(code)}, Player)
	}, func(r Response) bool {
		return !(r.Status == http.StatusUnprocessableEntity && r.Str("error") == "invalid_code")
	})
}

// FreeBonus is the operator's award with no offer (BonusRoutes' "Free Bonus" shape) of `amount` at
// `wager` times, as a step or as setup.
func (s *Session) FreeBonus(name string, amount int64, wager string, setup bool) Response {
	body := map[string]any{"bonusAmount": Decimal(amount), "wager": wager, "currency": Currency}
	path := s.Bonus() + "/grant/" + s.Brand() + "/" + s.Value("playerId")
	if setup {
		return s.Setup(name, "casino", http.MethodPost, path, body, Operator)
	}
	return s.Casino(name, http.MethodPost, path, body, Operator)
}

// FreeRounds is the operator's free rounds with no offer (shape B) of `count` rounds at 0.10 on `game`,
// paying winnings to real balance, as a step or as setup; a grant is captured as `grantId`.
func (s *Session) FreeRounds(name, game string, count int, setup bool) Response {
	body := map[string]any{
		"type": "FREE_SPINS", "gameId": game, "provider": "ST8", "spinCount": count, "spinValue": "0.10",
		"winningsDestination": "real_balance", "freeSpinsExpiryDays": 7,
	}
	path := s.Bonus() + "/grant/" + s.Brand() + "/" + s.Value("playerId")
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, path, body, Operator)
	} else {
		r = s.Casino(name, http.MethodPost, path, body, Operator)
	}
	if r.Status == http.StatusOK && r.Str("grantId") != "" {
		s.Grab("grantId", "$.grantId", r.Str("grantId"))
	}
	return r
}

// Provisioned reads fake-st8's record of the bonus it holds under the grant's id, until `until` holds.
func (s *Session) Provisioned(name string, until func(Response) bool) Response {
	return s.Observe(name, "fake-st8", http.MethodGet, "/test/bonus/"+s.Value("grantId"), NoAuth, until)
}

// Instance is the player's instance of a bonus fake-st8 holds: its status and the rounds remaining.
func (s *Session) Instance(r Response) (status string, remaining int) {
	inst, _ := r.Body["instances"].(map[string]any)
	p, _ := inst[s.Value("playerId")].(map[string]any)
	status, _ = p["status"].(string)
	n, _ := p["remaining"].(float64)
	return status, int(n)
}

// ClaimOf waits until the free rounds grant names its reward (`claim_ref`, written once the route has
// answered) and captures the reward's leaf id as `claimId`, which the revoke route takes.
func (s *Session) ClaimOf() {
	r := s.Observe("the free rounds name their reward", "substrate", http.MethodGet, "/twins/free-spins-grants/"+s.Value("grantId"), NoAuth,
		func(r Response) bool { return r.Status == http.StatusOK && r.Prop("claim_ref") != "" })
	if ref := r.Prop("claim_ref"); ref != "" {
		s.Grab("claimId", "$.claim_ref", ref[strings.LastIndex(ref, "/")+1:])
	}
}

// Rewards is the operator's read of the player's rewards (`backofficePlayerBonusesRoute`), read again
// until `until` holds of it: the grant store follows the award by moments.
func (s *Session) Rewards(name string, setup bool, until func(Response) bool) Response {
	path := s.Backoffice() + "/players/" + s.Value("playerId") + "/bonuses"
	return s.Eventually(func() Response {
		if setup {
			return s.Setup(name, "casino", http.MethodGet, path, nil, Operator)
		}
		return s.Casino(name, http.MethodGet, path, nil, Operator)
	}, until)
}

// Row is the first row of a list of rewards whose status is `status`, or nil.
func Row(list []any, status string) map[string]any {
	for _, e := range list {
		if m, ok := e.(map[string]any); ok && m["status"] == status {
			return m
		}
	}
	return nil
}

// Granted waits for the player's free bonus to be listed active and captures its leaf id as `claimId`.
func (s *Session) Granted() {
	r := s.Rewards("read the player's rewards", true, func(r Response) bool { return Row(r.List, "ACTIVE") != nil })
	if row := Row(r.List, "ACTIVE"); row != nil {
		ref, _ := row["grantRef"].(string)
		s.Grab("claimId", "$[0].grantRef", ref[strings.LastIndex(ref, "/")+1:])
	}
}

// Revoke is the operator's revoke of the reward captured as `claimId`, with `body` ({"mode": …}).
func (s *Session) Revoke(name string, body map[string]any) Response {
	return s.Casino(name, http.MethodPatch, "/v1/casino/"+s.Brand()+"/bonuses/"+s.Value("claimId")+"/revoke", body, Operator)
}

// CRMKey writes the made-up webhook signing key to the brand's legacy CRM config twin, as scenario 21
// does, and puts back what was there after the scenario.
func (s *Session) CRMKey() {
	twin := "/twins/casino/" + NameUUID("merchant.crm-config/"+s.Brand())
	before := s.Setup("read the brand's legacy CRM config", "substrate", http.MethodGet, twin, nil, NoAuth).Prop("crm_config")
	cfg, _ := json.Marshal(map[string]string{"webhookSigningKey": WebhookKey})
	s.ok(s.Setup("the brand's webhook signing key", "substrate", http.MethodPost, twin, map[string]any{"crm_config": string(cfg)}, NoAuth))
	s.Cleanup(func() {
		s.Setup("restore the brand's legacy CRM config", "substrate", http.MethodPost, twin, map[string]any{"crm_config": before}, NoAuth)
	})
}

// DetectionOff turns the brand's kill switch on in Unleash: the flag is created when missing, given a
// strategy for this brand alone, and enabled; after the scenario the strategy is removed, and the flag
// disabled again when it was off before. With no Unleash token the scenario is skipped before its first
// step.
func (s *Session) DetectionOff() {
	if s.cfg.UnleashToken == "" || s.cfg.UnleashURL == "" {
		s.Skip("no EITHEROR_UNLEASH_TOKEN or -unleash-url: the brand's kill switch is set in the original's Unleash")
		return
	}
	project := "/api/admin/projects/" + s.cfg.UnleashProject + "/features"
	feature := project + "/" + KillSwitch
	env := feature + "/environments/" + s.cfg.UnleashEnvironment
	r := s.Unleash("read the kill switch", http.MethodGet, feature, nil)
	wasOn := false
	if r.Status == http.StatusNotFound {
		s.ok(s.Unleash("create the kill switch", http.MethodPost, project, map[string]any{"name": KillSwitch, "type": "kill-switch"}))
	} else if envs, ok := r.Body["environments"].([]any); ok {
		for _, e := range envs {
			if m, ok := e.(map[string]any); ok && m["name"] == s.cfg.UnleashEnvironment {
				wasOn, _ = m["enabled"].(bool)
			}
		}
	}
	strategy := s.ok(s.Unleash("turn detection off for the brand", http.MethodPost, env+"/strategies", map[string]any{
		"name": "flexibleRollout",
		"constraints": []map[string]any{
			{"contextName": "brandId", "operator": "IN", "values": []string{s.Brand()}},
		},
		"parameters": map[string]string{"rollout": "100", "stickiness": "default", "groupId": KillSwitch},
	}))
	if id := strategy.Str("id"); id != "" {
		s.Remember("strategyId", id)
		s.Cleanup(func() { s.Unleash("remove the brand's strategy", http.MethodDelete, env+"/strategies/"+id, nil) })
	}
	s.ok(s.Unleash("enable the kill switch", http.MethodPost, env+"/on", nil))
	if !wasOn {
		s.Cleanup(func() { s.Unleash("disable the kill switch", http.MethodPost, env+"/off", nil) })
	}
}
