package engagementdriver

import (
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

// A scenario the original answers otherwise than its feature expects fails in the run's result and is
// still recorded: the recording is the original's behaviour, and the failure is a finding for the spec.

// Def is one recording: a feature scenario, one example of an outline, or a route no feature names.
type Def struct {
	Name        string // as features/engagement/*.feature names the scenario
	Example     string // an outline's example, by its first column; empty for a scenario
	Requirement string
	// Unfeatured says why no feature scenario names this recording, for a route the spec requires
	// that no scenario of features/engagement/ states; empty for a feature's scenario.
	Unfeatured string
	Run        func(t testing.TB, s *Session)
}

// Scenario is the recording's name: the feature's, with an outline's example in brackets.
func (d Def) Scenario() string {
	if d.Example == "" {
		return d.Name
	}
	return d.Name + " [" + d.Example + "]"
}

// Reasons a feature scenario is not driven here.
const (
	suite     = "the original's Go end-to-end suite reaches it, recorded with crm-stub's calls attached (research R2 item 1): "
	sendRec   = "the send record and its claim are this port's (spec, Changes from the original): the original keeps no record of a send"
	parked    = "the dead letter with an operator's retry and discard is this port's (spec, Changes from the original): the original's Scala path swallows every failure and its Go path drops one after four attempts"
	outbound  = "outbound marketing suppression is this port's (spec, Changes from the original; plan, Divergences): the original sends every trigger whatever the player's cases or consent"
	pushFact  = "the delivered fact is this port's casino.push (R11, a marked divergence): the original's delivery.outcome goes to platform.push.events per pod, which no HTTP route shows"
	revalidat = "the original's re-check fails only when the token stops verifying, which a realm token does at its expiry, about the recycle age on the kind realm; RevalidatorSpec's cases are ported (R2 item 3)"
)

// NotDriven are the feature scenarios this driver does not record, and why: some are recorded from the
// original's own suite (spec 014, Recordings), the rest are this port's own behaviour, which the
// original cannot show, and are held by the port's own suites.
var NotDriven = map[string]string{
	// campaigns.feature
	"a bonus action with a valid signature and time names a player and an active offer, and the brand's rewards are asked to award it as the CRM's": suite + "TestScenarioFreeSpinsPlay_CustomerIOGrant, scenario_free_spins_stacking (grant_bonus answered ok)",
	"a bonus action for an offer the player already holds a claim of is answered ok with already processed":                                         suite + "TestScenarioFreeSpinsPlay_CustomerIOGrant (the retried grant_bonus answered alreadyProcessed)",
	// operators.feature
	"an operator reads a player's sends newest first with event, class, channel, time, outcome and reason":                sendRec,
	"a parked send retried and accepted by the CRM becomes accepted and is no longer parked":                              parked,
	"a parked send discarded records who discarded it and why":                                                            parked,
	"a parked marketing class send retried for a player suppressed since is refused, audited and leaves the parked sends": parked + "; " + outbound,
	// profiles.feature
	"the identify before any track carries every attribute whose source fact has been seen, as numbers where numeric": suite + "every scenario that registers, deposits or sets a limit (TestScenario16_RewardGrant_TargetSelectActivateForfeit, TestScenario21_*, the TestScenario26-28 of e2e-rg-kyc.patch), each track preceded by its identify; G/handler_test.go's attribute encoding is ported (R2 item 3)",
	"a player with no row in the balance view is identified with the balance attributes absent, not zero":             "the balance view built from facts is this port's (R9): the original reads the wallet at every identify (CrmService.fetchWalletProperties)",
	// push.feature
	"an inbox item for a player with no connection anywhere pushes nothing and produces no fact": pushFact + "; nothing pushed to no connection is nothing a run can read; EnvelopeConsumerSpec's case is ported",
	"a connection whose sign-in no longer verifies at the re-check is closed as invalid":         revalidat,
	// suppression.feature
	"a marketing class trigger for a player with an open case is refused as suppressed and audited":            outbound,
	"a service class trigger for a player with an open case is sent":                                           suite + "TestScenario27_RGBreaks_* and TestScenario26_RGLimits_* of e2e-rg-kyc.patch: the original sends every trigger with a case open",
	"a player who withdrew email consent is identified as unsubscribed and a marketing class email is refused": outbound + "; the identify with email_subscribed false follows the consent PATCH (PlayerRoutes.scala:1789), recorded in spec 008's players recordings with crm-stub attached",
	"a closed player is refused every trigger but the closure confirmation":                                    outbound,
	"an exclusion ending identifies the player as not suppressed and marketing resumes subject to consent":     outbound + "; `marketing_suppressed` and `suppression_reason` are new attributes (wire.md)",
	// tracks.feature
	"a deposit confirmed fact makes one identify, one track and one transactional send, and a send record names them": suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit and TestScenario21_* (deposit_confirmed); " + sendRec,
	"the same fact delivered again makes no call and leaves the send record unchanged":                                sendRec + "; the Go path's dedup ids are G/handler_test.go's, ported (R2 item 3)",
	"a deposit that makes the lifetime count one is tracked as a first deposit beside the deposit":                    suite + "TestScenario16_RewardGrant_TargetSelectActivateForfeit (the player's first deposit)",
	"a fact older than the staleness bound at first sight is skipped as stale":                                        "a run cannot make a fact old at first sight; G/handler_test.go's freshness cases are ported (R2 item 3)",
	"a brand with no CRM configured sends nothing and names the gap on its status":                                    "the status route and its refusal counter are this port's (spec, Changes from the original); the original logs and returns",
	"a retryable failure from the CRM is retried with backoff and then becomes a parked send":                         parked + "; G/cio_client.go's retries are ported (R1)",
	// transactional.feature
	"a request naming the email address verification trigger makes one transactional send with the verification address and answers accepted": suite + "every scenario that registers a player (TestScenario16_RewardGrant_TargetSelectActivateForfeit first): registration's EmailAddressVerification send at crm-stub; the answer accepted is the port's send route's",
	"the same request again under the same key makes no send and answers the first outcome":                                                   sendRec,
	"a trigger with no transactional id and no fallback sends nothing and answers not configured":                                             "the send route's answers are this port's: the original's sends are in-process calls that answer nothing",
	"a request from a caller that is not a part of the brand is refused":                                                                      "the send route is this port's (001 FR-020 service class): the original's sends are in-process calls",
}

// Scenarios is every recording the driver makes, in the features' order, then the unfeatured ones.
func Scenarios() []Def {
	var defs []Def
	defs = append(defs, campaigns()...)
	defs = append(defs, operators()...)
	defs = append(defs, profiles()...)
	defs = append(defs, push()...)
	defs = append(defs, unfeatured()...)
	return defs
}

// signed is a webhook body sent under the brand's key.
func (s *Session) signed(name string, body map[string]any) Response {
	return s.Webhook(name, WebhookKey, Signed, body)
}

// noInbox reads the player's inbox once and wants it empty: a refused request did nothing.
func (s *Session) noInbox(w Who) {
	r := s.Want(s.Inbox("the player reads their inbox", w), http.StatusOK)
	if t := Total(r); t != "" && t != "0" {
		s.t.Errorf("%s: the inbox holds %s items after a refused request: %s", s.rec.Scenario, t, string(r.Raw))
	}
}

// campaigns.feature: the webhook (014/FR-017, FR-018).
func campaigns() []Def {
	refused := func(example string, sig Signature, key bool, want string) Def {
		return Def{Name: "a request whose signature is missing, malformed or wrong, or for a brand with no signing key, is refused as unauthenticated and does nothing",
			Example: example, Requirement: "014/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				if key {
					s.SigningKey(WebhookKey)
				} else {
					s.SigningKey("")
				}
				r := s.Webhook("the CRM posts a send action "+map[Signature]string{
					Signed: "signed under the brand's key", Unsigned: "with no signature",
					Malformed: "with a malformed signature", WrongKey: "signed under another key",
				}[sig], WebhookKey, sig, s.Send("", "deliveryId"))
				s.WantField(s.Want(r, http.StatusUnauthorized), "error", want)
				s.noInbox("")
			}}
	}
	return []Def{
		{Name: "a bonus action for an offer the brand's rewards refuse carries their reason and awards nothing", Requirement: "014/FR-018",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				s.SigningKey(WebhookKey)
				r := s.signed("the CRM awards an offer the brand does not hold", map[string]any{
					"action": "grant_bonus", "player_id": s.Value("playerId"), "offer_id": s.UUID("offerId")})
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "offer_not_found")
				rw := s.Want(s.Casino("an operator reads the player's rewards", http.MethodGet,
					s.Backoffice()+"/players/"+s.Value("playerId")+"/bonuses", nil, Operator), http.StatusOK)
				if len(rw.List) != 0 {
					s.t.Errorf("%s: the player holds %d rewards after a refused award", s.rec.Scenario, len(rw.List))
				}
			}},
		{Name: "a send action with a delivery id, a title and a body writes one inbox item of the marketing class, and a repeat writes nothing", Requirement: "014/FR-018",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				s.SigningKey(WebhookKey)
				send := s.Send("", "deliveryId")
				s.WantField(s.Want(s.signed("the CRM sends the player a message", send), http.StatusOK), "ok", "true")
				r := s.signed("the CRM sends the same message again", send)
				s.WantField(s.Want(r, http.StatusOK), "alreadyProcessed", "true")
				inbox := s.Eventually(func() Response { return s.Inbox("the player reads their inbox", "") },
					func(r Response) bool { return Total(r) == "1" })
				s.Want(inbox, http.StatusOK)
				// The original's inbox names no class: a CRM send is the item whose origin is the campaign.
				if Total(inbox) != "1" || !strings.Contains(string(inbox.Raw), `"originName":"Customer.io campaign"`) {
					s.t.Errorf("%s: the inbox is not one marketing item: %s", s.rec.Scenario, string(inbox.Raw))
				}
				// The player records' refusals, as the webhook answers them.
				s.ok(s.Setup("the player's messages are suppressed by an operator", "substrate", http.MethodPost, s.PlayerTwin(""),
					map[string]any{"message_suppressed": "true"}, NoAuth))
				s.WantField(s.Want(s.signed("the CRM sends the suppressed player a message", s.Send("", "suppressedDeliveryId")),
					http.StatusUnprocessableEntity), "error", "marketing_suppressed")
				s.WantField(s.Want(s.signed("the CRM sends a message to a player the brand does not hold", map[string]any{
					"action": "send_message", "player_id": s.UUID("unknownPlayerId"), "delivery_id": s.ID("unknownDeliveryId"),
					"title": "A message from your casino", "body": "Something new is waiting in the lobby."}),
					http.StatusUnprocessableEntity), "error", "player_not_found")
			}},
		{Name: "a send action without a delivery id is refused rather than given a fresh key", Requirement: "014/FR-018",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				s.SigningKey(WebhookKey)
				s.WantField(s.Want(s.signed("the CRM sends a message with no delivery id", s.Send("", "")),
					http.StatusUnprocessableEntity), "error", "delivery_id_required")
				s.noInbox("")
			}},
		refused("a brand with a signing key for its CRM webhook and a request with no signature", Unsigned, true, "invalid_signature"),
		refused("a brand with a signing key for its CRM webhook and a request with a malformed signature", Malformed, true, "invalid_signature"),
		refused("a brand with a signing key for its CRM webhook and a request with a wrong signature", WrongKey, true, "invalid_signature"),
		refused("a brand with no signing key for its CRM webhook", Signed, false, "webhook_not_configured"),
		{Name: "an unknown action with a valid signature is refused as unknown", Requirement: "014/FR-018",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				s.SigningKey(WebhookKey)
				s.WantField(s.Want(s.signed("the CRM posts an action the brand does not know", map[string]any{
					"action": "engagement_driver_unknown", "player_id": s.Value("playerId")}), http.StatusUnprocessableEntity),
					"error", "unknown_action")
			}},
	}
}

// operators.feature: the configuration page, the seed and presence (014/FR-026, FR-023).
func operators() []Def {
	return []Def{
		{Name: "a new CRM app key is stored in the secret store and read back masked, and a masked value sent back preserves the secret", Requirement: "014/FR-026",
			Run: func(t testing.TB, s *Session) {
				s.KeepConfig()
				page := s.Want(s.Casino("an operator opens the brand's CRM configuration", http.MethodGet, s.CRMConfig(), nil, Operator), http.StatusOK)
				key := s.ID("appKey")
				body := map[string]any{}
				for k, v := range page.Body {
					body[k] = v
				}
				body["appApiKey"] = key
				s.WantField(s.Want(s.Casino("the operator saves a new app key", http.MethodPut, s.CRMConfig(), body, Operator), http.StatusOK), "ok", "true")
				again := s.Want(s.Casino("the operator opens the configuration again", http.MethodGet, s.CRMConfig(), nil, Operator), http.StatusOK)
				s.WantField(again, "appApiKey", Mask(key))
				s.WantField(s.Want(s.Casino("the operator saves the page unchanged, the app key masked", http.MethodPut, s.CRMConfig(), again.Body, Operator),
					http.StatusOK), "ok", "true")
				if stored := str(s.StoredConfig(), "appApiKey"); stored != key {
					s.t.Errorf("%s: the stored app key is not the one saved after a masked value was sent back (%d characters)", s.rec.Scenario, len(stored))
				}
				s.WantField(s.Want(s.Casino("the operator opens the configuration a third time", http.MethodGet, s.CRMConfig(), nil, Operator),
					http.StatusOK), "appApiKey", Mask(key))
			}},
		{Name: "seeding the track list fires one identify and one track per trigger at the seed person, and seeding twice updates the same person", Requirement: "014/FR-026",
			Run: func(t testing.TB, s *Session) {
				person := "_seed_" + s.Brand()
				for i, name := range []string{"an operator seeds the track list", "the operator seeds it again"} {
					mark := len(s.CRM())
					r := s.Want(s.Casino(name, http.MethodPost, s.CRMConfig()+"/seed", nil, Operator), http.StatusOK)
					s.WantField(r, "person", person)
					seeded := r.Str("seeded")
					identifies, tracks := 0, 0
					for _, c := range s.CRMFor(mark, person) {
						switch c.Action() {
						case "identify":
							identifies++
						case "event":
							tracks++
						}
					}
					if n := strings.TrimSpace(seeded); n == "" || strconv.Itoa(identifies) != n || strconv.Itoa(tracks) != n {
						s.t.Errorf("%s: seed %d answered %q and the CRM received %d identifies and %d tracks for %s",
							s.rec.Scenario, i+1, seeded, identifies, tracks, person)
					}
				}
			}},
		{Name: "an operator reading presence sees each player with an open connection as online with the time they came online", Requirement: "014/FR-023",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				st := s.Stream("the player's page opens the stream", "")
				p := s.Want(s.Push("an operator reads presence", http.MethodGet, "/v1/presence", Operator), http.StatusOK)
				if p.Status == http.StatusOK && !s.Contains(p, "") {
					s.t.Errorf("%s: presence does not list the player: %s", s.rec.Scenario, string(p.Raw))
				}
				// The time they came online is the backoffice's online filter's (PresenceView's lastSeenAt),
				// fed from push-gateway's presence events.
				list := s.Eventually(func() Response {
					return s.Casino("an operator lists the brand's online players", http.MethodGet,
						s.Backoffice()+"/players?online=true&pageSize=200", nil, Operator)
				}, func(r Response) bool { return r.Status == http.StatusOK && s.Contains(r, "") })
				if s.Want(list, http.StatusOK).Status == http.StatusOK && !s.Contains(list, "") {
					s.t.Errorf("%s: the online list does not name the player: %s", s.rec.Scenario, string(list.Raw))
				}
				st.Close()
			}},
	}
}

// profiles.feature: what the original identifies outside a dispatch (014/FR-009, FR-010).
func profiles() []Def {
	return []Def{
		{Name: "the deposit day rolling over identifies a player who deposited today with a daily deposit count of zero, and not one already at zero", Requirement: "014/FR-009",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				s.Active("other")
				now := s.cfg.Now()
				s.Remember("today", DepositDay(now))
				s.Remember("yesterday", DepositDay(now.Add(-24*time.Hour)))
				s.ok(s.Setup("the player deposited twice on the deposit day ending", "substrate", http.MethodPost, s.PlayerTwin(""),
					map[string]any{"daily_deposit_count": "2", "daily_deposit_count_day": s.Value("yesterday")}, NoAuth))
				s.ok(s.Setup("the other player's count is already zero", "substrate", http.MethodPost, s.PlayerTwin("other"),
					map[string]any{"daily_deposit_count": "0", "daily_deposit_count_day": s.Value("yesterday")}, NoAuth))
				s.CRMSettled(s.Value("playerId"))
				mark := s.CRMSettled(s.Value("otherPlayerId"))
				s.Want(s.Internal("the deposit day rolls over", http.MethodPost, "/internal/v1/casino/"+s.Brand()+"/daily-deposit-count/reset", nil), http.StatusOK)
				s.Observe("the player's count is zero for the new deposit day", "substrate", http.MethodGet, s.PlayerTwin(""), NoAuth,
					func(r Response) bool {
						return r.Prop("daily_deposit_count") == "0" && r.Prop("daily_deposit_count_day") == s.Value("today")
					})
				zero := 0
				for _, c := range s.CRMFor(mark, s.Value("playerId")) {
					if c.Action() == "identify" && c.Attr("daily_deposit_count") == "0" {
						zero++
					}
				}
				if zero != 1 {
					s.t.Errorf("%s: %d identifies with daily_deposit_count 0 for the player, want 1", s.rec.Scenario, zero)
				}
				for _, c := range s.CRMFor(mark, s.Value("otherPlayerId")) {
					if c.Action() == "identify" {
						s.t.Errorf("%s: the player already at zero was identified: %s", s.rec.Scenario, string(c.Body))
					}
				}
			}},
		{Name: "a tier change identifies the player with the new value and tracks nothing", Requirement: "014/FR-010",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				mark := s.CRMSettled(s.Value("playerId"))
				s.WantField(s.Want(s.Casino("an operator changes the player's tier", http.MethodPut,
					"/v1/casino/"+s.Brand()+"/players/"+s.Value("playerId")+"/vip-tier", map[string]any{"tier": Tier}, Operator),
					http.StatusOK), "vipTier", Tier)
				// syncAttributes is fired after the answer; what follows it is waited out.
				s.CRMSettled(s.Value("playerId"))
				identified := false
				for _, c := range s.CRMFor(mark, s.Value("playerId")) {
					switch c.Action() {
					case "identify":
						identified = identified || c.Attr("vip_tier") == Tier
					case "event":
						s.t.Errorf("%s: a tier change made a track: %s", s.rec.Scenario, string(c.Body))
					}
				}
				if !identified {
					s.t.Errorf("%s: no identify carried vip_tier %q", s.rec.Scenario, Tier)
				}
			}},
	}
}

// push.feature: the player's stream (014/FR-020, FR-021).
func push() []Def {
	signal := func(st *Stream, s *Session, what string) {
		st.Until(what, s.cfg.StreamPatience, Kind("signal", "kind", "inbox.changed"))
	}
	return []Def{
		{Name: "a player opening the stream with their sign-in is answered ready, kept alive, and told before any close", Requirement: "014/FR-020",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				st := s.Stream("the player opens the stream", "")
				st.Until("a heartbeat", s.cfg.StreamPatience, func(fs []Frame) bool {
					for _, f := range fs {
						if strings.HasPrefix(f.Comment, "heartbeat") {
							return true
						}
					}
					return false
				})
				st.Close()
				if r := FirstOf(st.Frames(), "ready", "", ""); r != nil && !strings.Contains(r.Field("subjectId"), s.Value("playerId")) {
					s.t.Errorf("%s: the ready frame names %q", s.rec.Scenario, r.Field("subjectId"))
				}
			}},
		{Name: "an inbox item created reaches each of the player's connections on two instances as one inbox changed push, and one delivered fact counts them", Requirement: "014/FR-022",
			Run: func(t testing.TB, s *Session) {
				// The kind stack runs one push-gateway: the two connections are on its one instance.
				s.Active("")
				a := s.Stream("the player's first device opens the stream", "")
				b := s.Stream("the player's second device opens the stream", "")
				s.Want(s.Message("an operator sends the player an inbox item", "", false), http.StatusCreated)
				signal(a, s, "the first device's push")
				signal(b, s, "the second device's push")
				a.Close()
				b.Close()
				for i, st := range []*Stream{a, b} {
					n := 0
					for _, f := range st.Frames() {
						if f.Event == "signal" {
							n++
						}
					}
					if n != 1 {
						s.t.Errorf("%s: connection %d had %d pushes, want 1", s.rec.Scenario, i+1, n)
					}
				}
			}},
		{Name: "a connection past its recycle age is closed as recycled and the page reconnects", Requirement: "014/FR-021",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				st := s.Stream("the player opens the stream", "")
				st.Until("the closing frame", s.cfg.RecycleWait, Kind("closing", "", ""))
				if !st.WaitEnd(10 * time.Second) {
					s.t.Errorf("%s: the original did not end the stream after its closing frame", s.rec.Scenario)
				}
				st.Close()
				if c := FirstOf(st.Frames(), "closing", "", ""); c != nil && c.Field("reason") != "connection_recycled" {
					s.t.Errorf("%s: closed with %q", s.rec.Scenario, c.Field("reason"))
				}
				s.Stream("the page opens the stream again", "").Close()
			}},
		{Name: "an operator's or a part's sign-in without a player id is refused the stream", Requirement: "014/FR-020",
			Run: func(t testing.TB, s *Session) {
				st := s.Open("an operator opens the stream", "push", "/v1/stream", Operator)
				st.Close()
				s.WantField(s.Want(st.Response(), http.StatusForbidden), "error", "subject_unresolved")
			}},
		{Name: "an inbox item read on one device pushes inbox changed to every other connection so the unread count converges", Requirement: "014/FR-022",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				s.Want(s.Message("an operator sends the player an inbox item", "", true), http.StatusCreated)
				a := s.Stream("the player's first device opens the stream", "")
				b := s.Stream("the player's second device opens the stream", "")
				s.WantField(s.Want(s.Casino("the player reads the item on the first device", http.MethodPost,
					s.Players()+"/messages/"+s.Value("messageId")+"/read", nil, Player), http.StatusOK), "ok", "true")
				signal(b, s, "the second device's push")
				// The original pushes to every connection, the reading device's too: read for as long.
				signal(a, s, "the first device's push")
				a.Close()
				b.Close()
			}},
	}
}

// unfeatured: routes the spec requires and no scenario of features/engagement/ names.
func unfeatured() []Def {
	return []Def{
		{Name: "an agent's stream opened with a ticket is told when a player signs in, and the used ticket is refused",
			Requirement: "014/FR-024",
			Unfeatured:  "FR-024 (the agent's player online alert, kept 2026-11-03) has no scenario in features/engagement/",
			Run: func(t testing.TB, s *Session) {
				s.Registered("")
				tk := s.Want(s.Casino("an agent asks for a stream ticket", http.MethodPost, s.Backoffice()+"/agent-stream/ticket", nil, Operator), http.StatusOK)
				if tk.Str("ticket") == "" {
					return
				}
				s.Grab("ticket", "$.ticket", tk.Str("ticket"))
				s.WantField(tk, "expires_in", "30")
				st := s.Open("the agent's page opens the stream with the ticket", "casino", s.AgentStream(), NoAuth)
				if s.Want(st.Response(), http.StatusOK).Status == http.StatusOK {
					s.SignIn("", false)
					st.Until("the player online frame", s.cfg.StreamPatience, Kind("player_online", "player_id", s.Value("playerId")))
				}
				st.Close()
				again := s.Open("the agent's page opens the stream with the same ticket", "casino", s.AgentStream(), NoAuth)
				again.Close()
				s.WantField(s.Want(again.Response(), http.StatusForbidden), "error", "invalid_ticket")
			}},
		{Name: "a target action names a player and a role, and the brand's rewards target the role's offer at the player",
			Requirement: "014/FR-018",
			Unfeatured:  "campaigns.feature names the target action in its description and in no scenario",
			Run: func(t testing.TB, s *Session) {
				s.Active("")
				role := s.ID("role")
				s.Offer("offerId", map[string]string{"role": role})
				s.SigningKey(WebhookKey)
				// The role is found through the brand's offer list, which follows the offer's write by moments.
				r := s.Eventually(func() Response {
					return s.signed("the CRM targets the role's offer at the player", map[string]any{
						"action": "target_offer", "player_id": s.Value("playerId"), "role": role})
				}, func(r Response) bool { return r.Str("error") != "role_not_found" })
				s.WantField(s.Want(r, http.StatusOK), "ok", "true")
			}},
	}
}
