package engagementdriver

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// The original's engagement routes, one helper per route the scenarios use, each at the path the
// original serves:
//
//	GET|PUT /v1/casino/crm-config/{brand}, POST …/seed       CustomerIOConfigRoutes.scala:157-312
//	POST /v1/casino/customerio/webhook/{brand}               CustomerIOWebhookRoutes.scala:894-978
//	POST /internal/v1/casino/{brand}/daily-deposit-count/reset  CasinoServiceMain.scala:1048-1065
//	POST /v1/casino/backoffice/{brand}/agent-stream/ticket   BackofficeAgentStreamRoutes.scala:107-118
//	GET  /v1/casino/backoffice/{brand}/agent-stream?ticket=  BackofficeAgentStreamRoutes.scala:119-130
//	POST /v1/casino/backoffice/{brand}/players/{id}/messages PlayerProfileRoutes.scala:712-715,1369-1440
//	GET  /v1/casino/players/{brand}/{id}/messages            PlayerRoutes.scala:458,1270
//	POST /v1/casino/players/{brand}/messages/{id}/read       PlayerRoutes.scala:1373
//	PUT  /v1/casino/{brand}/players/{id}/vip-tier            BackofficePlayerRoutes.scala:2328
//	GET  /v1/casino/backoffice/{brand}/players?online=true   BackofficePlayerRoutes.scala:1558
//	GET  /v1/stream (push-gateway)                           StreamRoutes.scala:63-82
//	GET  /v1/presence (push-gateway)                         PushGatewayMain.scala:140-154

// The run's made-up values, the same on every run, so a replay sends them as they are.
const (
	Password = "Engagement-Driver-1!"
	Currency = "EUR"
	// WebhookKey is the made-up Customer.io signing key the driver saves to the brand's CRM
	// configuration; OtherKey signs a request under the wrong key. Neither is a credential of any system.
	WebhookKey = "engagement-driver-webhook-key"
	OtherKey   = "engagement-driver-another-key"
	// Tier is the tier an operator sets in the tier scenario.
	Tier = "gold"
)

// Players is the player routes' prefix; Backoffice the operator's; CRMConfig the configuration route.
func (s *Session) Players() string    { return "/v1/casino/players/" + s.Brand() }
func (s *Session) Backoffice() string { return "/v1/casino/backoffice/" + s.Brand() }
func (s *Session) CRMConfig() string  { return "/v1/casino/crm-config/" + s.Brand() }

// ok checks a setup write the original answers 2xx.
func (s *Session) ok(r Response) Response {
	if r.Status < 200 || r.Status > 299 {
		s.t.Errorf("%s: a setup step was answered %d: %s", s.rec.Scenario, r.Status, string(r.Raw))
	}
	return r
}

// Who names one of a scenario's players: "" is the player, any other word a second one. Its values
// are remembered as `{email}`, `{playerId}`, `{token}`, or `{<who>Email}`, `{<who>PlayerId}`, `{<who>Token}`.
type Who string

// label is the player's word in a step's name: nothing for the player.
func (w Who) label() string {
	if w == "" {
		return ""
	}
	return " " + string(w)
}

func (w Who) key(name string) string {
	if w == "" {
		return name
	}
	return string(w) + strings.ToUpper(name[:1]) + name[1:]
}

// Email, ID and Token are the remembered names of the player's address, id and sign-in.
func (w Who) Email() string { return w.key("email") }
func (w Who) ID() string    { return w.key("playerId") }
func (w Who) Token() Auth   { return Auth(w.key("token")) }

// Registered is the player registered (with no names, so the original computes no identity hash) and
// verified by the link the original would send, as setup.
func (s *Session) Registered(w Who) {
	s.t.Helper()
	body := map[string]any{
		"email": s.Email(w.Email()), "password": Password, "currency": Currency,
		"country": "GB", "language": "en", "dateOfBirth": "1990-01-01",
	}
	r := s.Want(s.Setup("register the player"+w.label(), "casino", http.MethodPost, s.Players()+"/register", body, NoAuth), http.StatusCreated)
	if r.Status != http.StatusCreated {
		return
	}
	s.Grab(w.ID(), "$.playerId", r.Str("playerId"))
	link := VerificationToken(s.cfg.SigningSecret, s.Brand(), s.Value(w.ID()), s.Value(w.Email()), s.cfg.Now().Add(24*time.Hour))
	s.Remember(w.key("link"), link)
	s.Want(s.Setup("follow the player's verification link"+w.label(), "casino", http.MethodPost, s.Players()+"/verify-email",
		map[string]any{"token": link}, NoAuth), http.StatusOK)
}

// SignIn signs the player in, as setup or as a compared step (the agent's stream is told of it).
func (s *Session) SignIn(w Who, setup bool) {
	s.t.Helper()
	body := map[string]any{"email": s.Value(w.Email()), "password": Password}
	var r Response
	if setup {
		r = s.Setup("sign the player in"+w.label(), "casino", http.MethodPost, s.Players()+"/login", body, NoAuth)
	} else {
		r = s.Casino("the player signs in", http.MethodPost, s.Players()+"/login", body, NoAuth)
	}
	if s.Want(r, http.StatusOK).Status == http.StatusOK {
		s.Grab(string(w.Token()), "$.token", r.Str("token"))
		s.Remember(w.key("refreshToken"), r.Str("refreshToken"))
	}
}

// Active is the player registered, verified and signed in, all as setup.
func (s *Session) Active(w Who) {
	s.Registered(w)
	if s.Value(w.ID()) != "" {
		s.SignIn(w, true)
	}
}

// PlayerTwin is the player's twin on substrate, where casino-service keeps the player's properties.
func (s *Session) PlayerTwin(w Who) string {
	return "/twins/casino-" + s.Brand() + "/" + s.Value(w.ID())
}

// configTwin is the brand's CRM configuration twin the configuration route reads and writes
// (SubstrateTwinClient.getCustomerIOConfig: `casino/crm-config/<brand>`, the id made a name UUID by
// resolvedEntityId); holdingTwin the holding's, which the webhook and the dispatch read first
// (getHoldingCrmConfig: `eitheror-holding/<nameUUID(seed.holding.<brand>)>`).
func (s *Session) configTwin() string {
	return "/twins/casino/" + NameUUID("merchant.crm-config/"+s.Brand())
}

func (s *Session) holdingTwin() string {
	return "/twins/eitheror-holding/" + NameUUID("seed.holding."+s.Brand())
}

// KeepConfig puts the brand's stored CRM configuration back as it was after the scenario, read and
// written on substrate with no step: it carries the brand's keys.
func (s *Session) KeepConfig() {
	if s.kept {
		return
	}
	s.kept = true
	before := s.quiet(http.MethodGet, s.configTwin(), nil).Prop("crm_config")
	s.Cleanup(func() { s.quiet(http.MethodPost, s.configTwin(), map[string]any{"crm_config": before}) })
}

// SigningKey saves `key` ("" for none) as the brand's webhook signing key through the configuration
// route, as the backoffice page saves it: the configuration is read masked, the key replaced, and the
// rest sent back masked so the original keeps what it holds. When the holding's configuration, which
// the webhook reads first, holds one, the key is written there too, with no step. Both are put back
// afterwards.
func (s *Session) SigningKey(key string) {
	s.KeepConfig()
	cfg := s.ok(s.Setup("read the brand's CRM configuration", "casino", http.MethodGet, s.CRMConfig(), nil, Operator))
	body := map[string]any{}
	for k, v := range cfg.Body {
		body[k] = v
	}
	body["webhookSigningKey"] = key
	what := "the brand's webhook signing key"
	if key == "" {
		what = "the brand has no webhook signing key"
	}
	s.ok(s.Setup(what, "casino", http.MethodPut, s.CRMConfig(), body, Operator))
	if raw := s.quiet(http.MethodGet, s.holdingTwin(), nil).Prop("crm_config"); raw != "" {
		m := map[string]any{}
		if json.Unmarshal([]byte(raw), &m) == nil {
			m["webhookSigningKey"] = key
			b, _ := json.Marshal(m)
			s.quiet(http.MethodPost, s.holdingTwin(), map[string]any{"crm_config": string(b)})
			s.Cleanup(func() { s.quiet(http.MethodPost, s.holdingTwin(), map[string]any{"crm_config": raw}) })
		}
	}
}

// StoredConfig is the brand's configuration as substrate holds it, read with no step.
func (s *Session) StoredConfig() map[string]any {
	m := map[string]any{}
	_ = json.Unmarshal([]byte(s.quiet(http.MethodGet, s.configTwin(), nil).Prop("crm_config")), &m)
	return m
}

// Send is a `send_message` action for the player under the delivery id minted as `delivery` ("" for
// none), as a Customer.io campaign's webhook body carries it.
func (s *Session) Send(w Who, delivery string) map[string]any {
	body := map[string]any{"action": "send_message", "player_id": s.Value(w.ID()),
		"title": "A message from your casino", "body": "Something new is waiting in the lobby.", "campaign_id": s.ID("campaignId")}
	if delivery != "" {
		body["delivery_id"] = s.ID(delivery)
	}
	return body
}

// Inbox is the player's own read of their inbox, as the player app reads it.
func (s *Session) Inbox(name string, w Who) Response {
	return s.Casino(name, http.MethodGet, s.Players()+"/"+s.Value(w.ID())+"/messages", nil, w.Token())
}

// Total is a list's `total`, as a number's text, or "" when it has none.
func Total(r Response) string { return r.Str("total") }

// Message is an operator's inbox item for the player of the service class (so no consent gates it),
// under a fresh idempotency key, as a step or as setup; its id is captured as `messageId`.
func (s *Session) Message(name string, w Who, setup bool) Response {
	body := map[string]any{"title": "Your account", "body": "An operator has left you a note.", "category": "service",
		"idempotencyKey": s.ID("messageKey")}
	path := s.Backoffice() + "/players/" + s.Value(w.ID()) + "/messages"
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, path, body, Operator)
	} else {
		r = s.Casino(name, http.MethodPost, path, body, Operator)
	}
	if r.Status == http.StatusCreated && r.Str("notificationId") != "" {
		s.Grab("messageId", "$.notificationId", r.Str("notificationId"))
	}
	return r
}

// Stream opens the player's push stream as the player app's proxy does (`/v1/stream` with the
// player's sign-in) and waits for its ready frame.
func (s *Session) Stream(name string, w Who) *Stream {
	st := s.Open(name, "push", "/v1/stream", w.Token())
	if s.Want(st.Response(), http.StatusOK).Status == http.StatusOK {
		st.Until("the ready frame", s.cfg.StreamPatience, Kind("ready", "", ""))
	}
	return st
}

// Offer writes an active deposit match offer with no deposit required under a fresh UUID `name`, as
// scenario 21's s21CreateOffer writes one, with `overrides` applied; it is deactivated after the
// scenario. Returns its id.
func (s *Session) Offer(name string, overrides map[string]string) string {
	id := s.UUID(name)
	now := s.cfg.Now().UTC()
	from, until := now.Add(-time.Hour).Format(time.RFC3339), now.Add(24*time.Hour).Format(time.RFC3339)
	s.Remember(name+"ValidFrom", from)
	s.Remember(name+"ValidUntil", until)
	props := map[string]string{
		"offer_id": id, "brand_id": s.Brand(), "name": "engagement driver " + name, "type": "DEPOSIT_MATCH",
		"payload_type": "bonus_funds", "is_active": "true", "currency": Currency, "valid_from": from, "valid_until": until,
		"min_deposit_amount": "10.00", "bonus_percentage": "100", "max_bonus_value": "500.00",
		"max_convertible_amount": "500.00", "wagering_multiplier": "1", "wagering_window_days": "30",
		"priority_order": "1", "max_claims_per_player": "1", "claim_window_days": "30", "excluded_offer_ids": "",
		"redemption_codes": "", "deposit_number_target": "ANY", "max_concurrent_grants": "1", "conflicts_with": "",
		"no_deposit_required": "true",
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

// AgentStream is the agent stream's path with the ticket captured as `ticket`.
func (s *Session) AgentStream() string {
	return s.Backoffice() + "/agent-stream?ticket=" + url.QueryEscape(s.Value("ticket"))
}

// Contains tells whether an answer names the player anywhere.
func (s *Session) Contains(r Response, w Who) bool {
	id := s.Value(w.ID())
	return id != "" && strings.Contains(string(r.Raw), id)
}
