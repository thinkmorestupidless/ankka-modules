package playersdriver

import (
	"net/http"
	"net/url"
	"testing"
)

// A scenario the original answers otherwise than its feature expects fails in the run's result and is
// still recorded: the recording is the original's behaviour, and the failure is a divergence the spec
// names (the original's closure, for one, does not restrict sign-in or launch).

// Def is one recording: a feature scenario, or one example of an outline.
type Def struct {
	Name        string // as features/players/*.feature names the scenario
	Example     string // an outline's example, by its first column; empty for a scenario
	Requirement string
	Run         func(t testing.TB, s *Session)
}

// Scenario is the recording's name: the feature's, with an outline's example in brackets.
func (d Def) Scenario() string {
	if d.Example == "" {
		return d.Name
	}
	return d.Name + " [" + d.Example + "]"
}

// Extra are recordings of routes the original serves that no feature scenario names (the operator's
// messages, merge tags and activity export): kept so the port's parity tests hold those routes too.
var Extra = map[string]bool{
	ExtraMessage:  true,
	ExtraMergeTag: true,
	ExtraExport:   true,
}

const (
	ExtraMessage  = "an operator's message to a player reaches the player's inbox"
	ExtraMergeTag = "an operator's message naming a merge tag the brand does not know is refused"
	ExtraExport   = "an operator's activity export of a player is prepared and read"
)

// NotDriven are the feature scenarios no recording of the original can show, and why (spec 008,
// *Recordings*). Each is held by the port's own suites.
var NotDriven = map[string]string{
	"the realm asking whether a restricted player may sign in is answered that they may not, before anything is issued [suspended]":     "the original's realm asks nothing; its check follows the issue",
	"the realm asking whether a restricted player may sign in is answered that they may not, before anything is issued [self-excluded]": "the original's realm asks nothing; its check follows the issue",
	"a player session whose realm session has gone idle ends as expired when the brand next asks the realm":                             "the realm's idle timeout is not waited by a run",
	"a refresh that is expired, reused or revoked is answered refresh expired [expired]":                                                "the realm's refresh lifetime is not waited by a run",
	"a game session's timer is set from the minutes left of the session limit":                                                          "the original has no timer per game session (its hourly rule is empty)",
	"a game session whose timer fires ends with reason limit":                                                                           "the original has no timer per game session (its hourly rule is empty)",
	"a brand with one session at a time ends the older player session on a new sign-in":                                                 "the original has no one-session setting",
	"an operator has no exclusion action on the player records themselves":                                                              "the original's exclusion route is the one spec 010 takes over; the refusal is the port's route table",
	"a player closes their own account and it is a closure, not a self-exclusion":                                                       "the original has no player closure route",
	"an operator reopens a closed account on the player's request within the hold":                                                      "the original has no reopen route",
	"a flagged player's claim of a reward is withheld by the brand's rewards from the flag they read":                                   "rewards' (spec 011)",
	"a brand with detection turned off compares nothing on registration":                                                                "the original has no detection switch",
	"a filed erasure exists in the brand and in the shared payments under one correlation id with the brand's hold":                     "the original has no erasure",
	"an erasure whose hold has lapsed and which every part has applied removes the realm user and assembles the certificates":           "the original has no erasure",
	"an erasure filed for a player with an open withdrawal is held until the withdrawal completes or fails":                             "the original has no erasure",
	"a player asking for their data receives an export with operator notes excluded":                                                    "the original has no player export (its export is the operator's, recorded as an extra)",
	"a registration with an erased player's email address creates a new player with a new id":                                           "the original has no erasure",
	"a player's export carries everything the brand holds, each service's part named":                                                   "the original has no player export",
	"a service that does not answer the export is named as missing, not left out":                                                       "the original has no player export",
}

// Scenarios is every recording the driver makes, in the features' order.
func Scenarios() []Def {
	defs := []Def{
		// registration.feature
		{Name: "a registration with a new email address, an acceptable password and an offered currency creates a player pending email verification", Requirement: "008/FR-001",
			Run: func(t testing.TB, s *Session) {
				r := s.Want(s.Register("register with a new address", "", map[string]any{
					"btag": "a_123b_456c_", "clickId": "click-1", "bonusCode": "WELCOME",
				}, false), http.StatusCreated)
				s.WantField(r, "status", "pending_email_verification")
			}},
		{Name: "a registration with an email address already registered is refused and nothing is created", Requirement: "008/FR-001",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Register("register the address", "", nil, true), http.StatusCreated)
				r := s.Casino("register the same address again", http.MethodPost, s.Players()+"/register", s.Registration("", nil), NoAuth, nil)
				s.WantField(s.Want(r, http.StatusConflict), "error", "email_already_exists")
			}},
		{Name: "a registration with a password below the realm's policy is refused and nothing is created", Requirement: "008/FR-002",
			Run: func(t testing.TB, s *Session) {
				r := s.Register("register with a weak password", "", map[string]any{"password": WeakPassword}, false)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "password_too_weak")
				s.Want(s.Login("sign in with the weak password", "", WeakPassword, false), http.StatusUnauthorized)
			}},
		{Name: "a registration carrying a tag that is not well formed succeeds with no tag on the fact", Requirement: "008/FR-003",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Register("register with a malformed tag", "", map[string]any{"btag": "<script>nan</script>"}, false), http.StatusCreated)
			}},
	}
	for _, field := range []struct{ example, name string }{
		{"the email address", "email"}, {"the password", "password"}, {"the currency", "currency"},
	} {
		field := field
		defs = append(defs, Def{Name: "a registration missing a required field is refused naming the three required fields", Example: field.example, Requirement: "008/FR-001",
			Run: func(t testing.TB, s *Session) {
				r := s.Casino("register without "+field.example, http.MethodPost, s.Players()+"/register", s.Registration("", map[string]any{field.name: nil}), NoAuth, nil)
				s.Want(r, http.StatusBadRequest)
			}})
	}
	defs = append(defs,
		Def{Name: "a registration with marketing consents given records them with their time and source", Requirement: "008/FR-028",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"emailMarketingConsent": true, "smsMarketingConsent": true})
				s.Want(s.Casino("read the consents", http.MethodGet, s.Players()+"/"+s.Value("playerId")+"/consent", nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "following the verification link within a day verifies the address and makes the player active, and following it again changes nothing", Requirement: "008/FR-005",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Register("register", "", nil, true), http.StatusCreated)
				link := s.Link("")
				s.WantField(s.Want(s.Casino("follow the verification link", http.MethodPost, s.Players()+"/verify-email", link, NoAuth, nil), http.StatusOK), "status", "verified")
				s.Want(s.Casino("follow the verification link again", http.MethodPost, s.Players()+"/verify-email", link, NoAuth, nil), http.StatusOK)
				s.Want(s.Login("sign in", "", Password, false), http.StatusOK)
				s.Want(s.Casino("read the profile", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "a registration keeps the network address and its country as personal data without overwriting the player's own country", Requirement: "008/FR-004",
			Run: func(t testing.TB, s *Session) {
				r := s.Casino("register from a network address", http.MethodPost, s.Players()+"/register",
					s.Registration("", map[string]any{"country": "DE"}), NoAuth, map[string]string{"X-Forwarded-For": NetworkAddress})
				if s.Want(r, http.StatusCreated).Status == http.StatusCreated {
					s.Grab("playerId", "$.playerId", r.Str("playerId"))
				}
				s.Want(s.Setup("follow the verification link", "casino", http.MethodPost, s.Players()+"/verify-email", s.Link(""), NoAuth), http.StatusOK)
				s.Want(s.Login("sign in", "", Password, true), http.StatusOK)
				s.WantField(s.Want(s.Casino("read the profile", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Player, nil), http.StatusOK), "country", "DE")
				s.Want(s.Casino("an operator reads the profile", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/profile", nil, Operator, nil), http.StatusOK)
			}},

		// sessions.feature
		Def{Name: "a sign-in with the right password opens a player session and answers the player's details", Requirement: "008/FR-012",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Register("register", "", map[string]any{"bonusCode": "WELCOME"}, true), http.StatusCreated)
				s.Want(s.Setup("follow the verification link", "casino", http.MethodPost, s.Players()+"/verify-email", s.Link(""), NoAuth), http.StatusOK)
				r := s.Want(s.Login("sign in with the right password", "", Password, false), http.StatusOK)
				s.WantField(r, "playerId", s.Value("playerId"))
				s.WantField(r, "currency", "EUR")
			}},
		Def{Name: "a sign-in by a player whose email address is not verified is refused and no session opens", Requirement: "008/FR-010",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Register("register", "", nil, true), http.StatusCreated)
				s.WantField(s.Want(s.Login("sign in unverified", "", Password, false), http.StatusUnauthorized), "error", "email_not_verified")
			}},
	)
	for _, st := range restricted(false) {
		st := st
		defs = append(defs, Def{Name: "a sign-in by a restricted player is refused naming the status and the attempt is kept", Example: st.example, Requirement: "008/FR-011",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				st.apply(s)
				r := s.Want(s.Login("sign in while "+st.example, "", Password, false), http.StatusForbidden)
				s.WantField(r, "error", "account_restricted")
				s.WantField(r, "reason", st.status)
			}})
	}
	defs = append(defs,
		Def{Name: "a sign-out ends the player session and every game session under it and revokes the realm's session", Requirement: "008/FR-014",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("open a game session", "", "sessionId", true), http.StatusOK)
				s.Want(s.Launch("open a second game session", "", "secondSessionId", true), http.StatusOK)
				s.WantField(s.Want(s.Casino("sign out", http.MethodPost, s.Players()+"/"+s.Value("playerId")+"/logout", nil, Player, nil), http.StatusOK), "status", "logged_out")
				s.Want(s.Casino("read the sessions", http.MethodGet, s.Sessions()+"?playerId="+s.Value("playerId"), nil, Player, nil), http.StatusOK)
				r := s.Casino("refresh the ended sign-in", http.MethodPost, s.Players()+"/refresh", map[string]any{"refreshToken": s.Value("refreshToken")}, NoAuth, nil)
				s.WantField(s.Want(r, http.StatusUnauthorized), "error", "refresh_expired")
			}},
		Def{Name: "a refresh of the sign-in issues a new one", Requirement: "008/FR-014",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				r := s.Want(s.Casino("refresh", http.MethodPost, s.Players()+"/refresh", map[string]any{"refreshToken": s.Value("refreshToken")}, NoAuth, nil), http.StatusOK)
				if r.Status == http.StatusOK {
					s.Grab("newToken", "$.accessToken", r.Str("accessToken"))
				}
			}},
		Def{Name: "a refresh that is expired, reused or revoked is answered refresh expired", Example: "reused", Requirement: "008/FR-014",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				body := map[string]any{"refreshToken": s.Value("refreshToken")}
				r := s.Want(s.Casino("refresh", http.MethodPost, s.Players()+"/refresh", body, NoAuth, nil), http.StatusOK)
				if r.Status == http.StatusOK {
					s.Remember("newRefreshToken", r.Str("refreshToken"))
				}
				s.WantField(s.Want(s.Casino("refresh with the same refresh again", http.MethodPost, s.Players()+"/refresh", body, NoAuth, nil), http.StatusUnauthorized), "error", "refresh_expired")
			}},
		Def{Name: "a refresh that is expired, reused or revoked is answered refresh expired", Example: "revoked", Requirement: "008/FR-014",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Setup("sign out", "casino", http.MethodPost, s.Players()+"/"+s.Value("playerId")+"/logout", nil, Player), http.StatusOK)
				r := s.Casino("refresh the revoked sign-in", http.MethodPost, s.Players()+"/refresh", map[string]any{"refreshToken": s.Value("refreshToken")}, NoAuth, nil)
				s.WantField(s.Want(r, http.StatusUnauthorized), "error", "refresh_expired")
			}},
		Def{Name: "a player's request about another player's id is refused as a mismatch", Requirement: "008/FR-015",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Active("other", nil)
				r := s.Casino("read the other player's profile", http.MethodGet, s.Players()+"/"+s.Value("otherPlayerId"), nil, Player, nil)
				s.WantField(s.Want(r, http.StatusForbidden), "error", "player_id_mismatch")
				r = s.Casino("sign the other player out", http.MethodPost, s.Players()+"/"+s.Value("otherPlayerId")+"/logout", nil, Player, nil)
				s.WantField(s.Want(r, http.StatusForbidden), "error", "player_id_mismatch")
			}},
		Def{Name: "a game session opened at launch for an active verified player is recorded under the player session", Requirement: "008/FR-017",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("launch a game", "", "sessionId", false), http.StatusOK)
				s.Want(s.Casino("read the game session", http.MethodGet, s.Sessions()+"/"+s.Value("sessionId"), nil, Player, nil), http.StatusOK)
			}},
	)
	for _, st := range restricted(true) {
		st := st
		defs = append(defs, Def{Name: "a game session asked for a restricted or closed player is refused naming the status", Example: st.example, Requirement: "008/FR-018",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				st.apply(s)
				r := s.Want(s.Launch("launch a game while "+st.example, "", "", false), http.StatusForbidden)
				s.WantField(r, "reason", st.status)
			}})
	}
	defs = append(defs,
		Def{Name: "a player ending a game session records its end time and duration, and ending it again changes nothing", Requirement: "008/FR-021",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("open a game session", "", "sessionId", true), http.StatusOK)
				end := s.Sessions() + "/" + s.Value("sessionId") + "/end"
				s.Want(s.Casino("end the game session", http.MethodPatch, end, nil, Player, nil), http.StatusOK)
				s.Want(s.Casino("end the game session again", http.MethodPatch, end, nil, Player, nil), http.StatusOK)
				s.Want(s.Casino("read the game session", http.MethodGet, s.Sessions()+"/"+s.Value("sessionId"), nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "an operator ending a game session by force ends it with reason forced and is named in the audit record", Requirement: "008/FR-021",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("open a game session", "", "sessionId", true), http.StatusOK)
				r := s.Casino("end the game session by force", http.MethodPost, s.Operator()+"/sessions/"+s.Value("sessionId")+"/force-end", nil, Operator, nil)
				s.WantField(s.Want(r, http.StatusOK), "status", "ended")
				s.Want(s.Casino("read the game session", http.MethodGet, s.Sessions()+"/"+s.Value("sessionId"), nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "a player reading their sessions sees their own and nobody else's", Requirement: "008/FR-021",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Active("other", nil)
				s.Want(s.Launch("open the player's game session", "", "sessionId", true), http.StatusOK)
				s.Want(s.Launch("open the other player's game session", "other", "otherSessionId", true), http.StatusOK)
				s.Want(s.Casino("read the sessions", http.MethodGet, s.Sessions()+"?playerId="+s.Value("playerId"), nil, Player, nil), http.StatusOK)
				r := s.Casino("read the other player's session", http.MethodGet, s.Sessions()+"/"+s.Value("otherSessionId"), nil, Player, nil)
				s.WantField(s.Want(r, http.StatusNotFound), "error", "session_not_found")
			}},
		Def{Name: "a brand with the default configuration lets two devices hold two player sessions", Requirement: "008/FR-012",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				first := s.Value("refreshToken")
				s.Remember("firstRefreshToken", first)
				s.Want(s.Login("sign in on a second device", "", Password, false), http.StatusOK)
				s.Want(s.Casino("refresh the first device's sign-in", http.MethodPost, s.Players()+"/refresh", map[string]any{"refreshToken": first}, NoAuth, nil), http.StatusOK)
			}},

		// profile.feature
		Def{Name: "a player reads their profile with every personal field readable to them and to no other player", Requirement: "008/FR-023",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"firstName": "Ada", "lastName": "Lovelace", "dateOfBirth": "1990-12-10"})
				s.Active("other", nil)
				s.Want(s.Casino("read the profile", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Player, nil), http.StatusOK)
				s.Want(s.Casino("another player reads it", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Other, nil), http.StatusForbidden)
			}},
		Def{Name: "a locked date of birth is unchanged by an edit, the other fields apply, and the fact names only what changed", Requirement: "008/FR-024",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"dateOfBirth": "1990-12-10"})
				s.Want(s.Casino("edit the date of birth and the city", http.MethodPatch, s.Players()+"/"+s.Value("playerId"),
					map[string]any{"dateOfBirth": "1980-01-01", "city": "Leeds", "firstName": "Ada"}, Player, nil), http.StatusOK)
				r := s.Want(s.Casino("read the profile", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Player, nil), http.StatusOK)
				s.WantField(r, "dateOfBirth", "1990-12-10")
				s.WantField(r, "city", "Leeds")
			}},
		Def{Name: "a country the brand does not allow is refused and nothing changes", Requirement: "008/FR-024",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"country": nil})
				r := s.Casino("set a country the brand does not allow", http.MethodPatch, s.Players()+"/"+s.Value("playerId"), map[string]any{"country": "XX"}, Player, nil)
				s.WantField(s.Want(r, http.StatusBadRequest), "error", "invalid_country")
				s.Want(s.Casino("read the profile", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "a second email address added and verified is verified and not the sign-in address", Requirement: "008/FR-026",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				secondAddress(s)
				s.Want(s.Casino("read the addresses", http.MethodGet, s.Players()+"/emails", nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "making a verified second email address the sign-in address makes the realm sign them in with it", Requirement: "008/FR-026",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				second := secondAddress(s)
				s.Want(s.Casino("make the second address the sign-in address", http.MethodPost, s.Players()+"/emails/"+url.PathEscape(second)+"/promote", nil, Player, nil), http.StatusOK)
				s.Want(s.Casino("sign in with the second address", http.MethodPost, s.Players()+"/login", map[string]any{"email": second, "password": Password}, NoAuth, nil), http.StatusOK)
				s.Want(s.Casino("sign in with the first address", http.MethodPost, s.Players()+"/login", map[string]any{"email": s.Value("email"), "password": Password}, NoAuth, nil), http.StatusUnauthorized)
			}},
		Def{Name: "a password change with the current password sends a confirmation email and changes the password only when its link is followed", Requirement: "008/FR-027",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				r := s.Casino("change the password", http.MethodPost, s.Players()+"/password-change", map[string]any{"currentPassword": Password}, Player, nil)
				s.WantField(s.Want(r, http.StatusAccepted), "status", "verification_email_sent")
				s.Want(s.Login("sign in with the old password before the link", "", Password, false), http.StatusOK)
				token := s.Twin("read the password change's token", "password_change_token")
				s.Remember("changeToken", token)
				r = s.Casino("follow the confirmation link", http.MethodPost, s.Players()+"/password-change/verify",
					map[string]any{"token": token, "playerId": s.Value("playerId"), "newPassword": NewPassword}, NoAuth, nil)
				s.WantField(s.Want(r, http.StatusOK), "status", "password_changed")
				s.Want(s.Login("sign in with the new password", "", NewPassword, false), http.StatusOK)
			}},
		Def{Name: "a password change with a wrong current password is refused", Requirement: "008/FR-027",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				r := s.Casino("change the password with a wrong current one", http.MethodPost, s.Players()+"/password-change", map[string]any{"currentPassword": NewPassword}, Player, nil)
				s.WantField(s.Want(r, http.StatusBadRequest), "error", "invalid_password")
			}},
		Def{Name: "a withdrawn email marketing consent reads as withdrawn with its time, and marketing email stops from that fact", Requirement: "008/FR-028",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"emailMarketingConsent": true})
				consent := s.Players() + "/" + s.Value("playerId") + "/consent"
				s.Want(s.Casino("withdraw email marketing consent", http.MethodPatch, consent, map[string]any{"emailMarketingConsent": false}, Player, nil), http.StatusOK)
				s.Want(s.Casino("read the consents", http.MethodGet, consent, nil, Player, nil), http.StatusOK)
			}},
		Def{Name: "a cookie decision recorded with every field is kept per device and visible to an operator", Requirement: "008/FR-028",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Casino("record the cookie decision", http.MethodPatch, s.Players()+"/"+s.Value("playerId")+"/cookie-consent", cookie(s, ""), Player, nil), http.StatusOK)
				s.Want(s.Casino("an operator reads the profile", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/profile", nil, Operator, nil), http.StatusOK)
			}},
		Def{Name: "a cookie decision with a field missing is refused rather than defaulted", Requirement: "008/FR-028",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				r := s.Casino("record a cookie decision without the version", http.MethodPatch, s.Players()+"/"+s.Value("playerId")+"/cookie-consent", cookie(s, "version"), Player, nil)
				s.WantField(s.Want(r, http.StatusBadRequest), "error", "invalid_cookie_consent_body")
			}},
		Def{Name: "an operator's correction of a date of birth that would make the player younger than eighteen is refused", Requirement: "008/FR-025",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"dateOfBirth": "1990-12-10"})
				young := s.cfg.Now().AddDate(-17, 0, 0).Format("2006-01-02")
				s.Remember("youngDate", young)
				r := s.Casino("correct the date of birth to under eighteen", http.MethodPatch, s.Operator()+"/date-of-birth",
					map[string]any{"dateOfBirth": young, "reason": "document shows otherwise"}, Operator, nil)
				s.WantField(s.Want(r, http.StatusBadRequest), "error", "underage")
			}},
		Def{Name: "an operator's correction of a date of birth to eighteen or over is applied and audited with the reason", Requirement: "008/FR-025",
			Run: func(t testing.TB, s *Session) {
				s.Active("", map[string]any{"dateOfBirth": "1990-12-10"})
				s.Want(s.Casino("correct the date of birth", http.MethodPatch, s.Operator()+"/date-of-birth",
					map[string]any{"dateOfBirth": "1990-10-12", "reason": "day and month transposed"}, Operator, nil), http.StatusOK)
				s.Want(s.Casino("an operator reads the profile", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/profile", nil, Operator, nil), http.StatusOK)
			}},

		// status.feature
		Def{Name: "a suspension with a reason ends every session, revokes the realm's sessions and carries the previous and new status", Requirement: "008/FR-032",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("open a game session", "", "sessionId", true), http.StatusOK)
				s.Want(s.Casino("suspend the player", http.MethodPatch, s.Operator()+"/suspend", map[string]any{"reason": "chargeback under review"}, Operator, nil), http.StatusOK)
				s.Want(s.Casino("read the sessions", http.MethodGet, s.Sessions()+"?playerId="+s.Value("playerId"), nil, Player, nil), http.StatusOK)
				r := s.Casino("refresh the suspended player's sign-in", http.MethodPost, s.Players()+"/refresh", map[string]any{"refreshToken": s.Value("refreshToken")}, NoAuth, nil)
				s.WantField(s.Want(r, http.StatusUnauthorized), "error", "refresh_expired")
			}},
		Def{Name: "a reinstatement makes a suspended player active", Requirement: "008/FR-032",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Setup("suspend the player", "casino", http.MethodPatch, s.Operator()+"/suspend", map[string]any{"reason": "chargeback under review"}, Operator), http.StatusOK)
				s.Want(s.Casino("reinstate the player", http.MethodPatch, s.Operator()+"/reinstate", map[string]any{}, Operator, nil), http.StatusOK)
				s.Want(s.Login("sign in", "", Password, false), http.StatusOK)
			}},
		Def{Name: "an exclusion applied through responsible gambling sets the status, keeps the exclusion time and ends every session", Requirement: "008/FR-031",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("open a game session", "", "sessionId", true), http.StatusOK)
				s.Want(s.Casino("exclude the player", http.MethodPost, s.Operator()+"/self-exclude", map[string]any{"period": "183d"}, Operator, nil), http.StatusOK)
				r := s.Want(s.Login("sign in", "", Password, false), http.StatusForbidden)
				s.WantField(r, "reason", "self_excluded")
			}},
		Def{Name: "a tier set on a player at no tier carries an empty previous tier and names the operator in the audit record", Requirement: "008/FR-033",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Casino("set tier gold", http.MethodPut, s.Operator()+"/vip-tier", map[string]any{"tier": "gold"}, Operator, nil), http.StatusOK)
				s.Want(s.Casino("an operator reads the profile", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/profile", nil, Operator, nil), http.StatusOK)
			}},
		Def{Name: "a closure ends everything, disables the realm user and is produced with actor and reason", Requirement: "008/FR-032",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Launch("open a game session", "", "sessionId", true), http.StatusOK)
				r := s.Casino("close the account", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/close", map[string]any{"reason": "player request by email"}, Operator, nil)
				s.WantField(s.Want(r, http.StatusOK), "status", "closed")
				s.Want(s.Casino("read the sessions", http.MethodGet, s.Sessions()+"?playerId="+s.Value("playerId"), nil, Player, nil), http.StatusOK)
				s.RealmUser("")
				s.Setup("read the realm user", "keycloak", http.MethodGet, "/admin/realms/casino-"+s.Brand()+"/users/"+s.Value("realmUser"), nil, Admin)
			}},
		Def{Name: "a closed player's sign-in is refused and their wallet balances are untouched", Requirement: "008/FR-011",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Setup("close the account", "casino", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/close", map[string]any{}, Operator), http.StatusOK)
				r := s.Want(s.Login("sign in while closed", "", Password, false), http.StatusForbidden)
				s.WantField(r, "error", "account_restricted")
			}},
		Def{Name: "an operator without the brand's role is refused any action on its player however they signed in", Requirement: "008/FR-032",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Casino("suspend without the brand's role", http.MethodPatch, s.Operator()+"/suspend", map[string]any{"reason": "x"}, Outsider, nil), http.StatusForbidden)
				s.Want(s.Casino("read the profile without the brand's role", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/profile", nil, Outsider, nil), http.StatusForbidden)
				s.Want(s.Casino("an operator action under the player's own sign-in", http.MethodPatch, s.Operator()+"/suspend", map[string]any{"reason": "x"}, Player, nil), http.StatusForbidden)
			}},
		Def{Name: "a pinned note is personal data under the player, first in the operator's view and invisible to the player", Requirement: "008/FR-034",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				r := s.Casino("write a note", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/notes",
					map[string]any{"content": "called about a late withdrawal", "authorName": "Driver"}, Operator, nil)
				if s.Want(r, http.StatusCreated).Status == http.StatusCreated {
					s.Grab("noteId", "$.noteId", r.Str("noteId"))
				}
				s.Want(s.Casino("pin the note", http.MethodPatch, s.Backoffice()+"/players/"+s.Value("playerId")+"/notes/"+s.Value("noteId")+"/pin", nil, Operator, nil), http.StatusOK)
				s.Want(s.Casino("an operator reads the profile", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/profile", nil, Operator, nil), http.StatusOK)
				s.Want(s.Casino("the player reads their profile", http.MethodGet, s.Players()+"/"+s.Value("playerId"), nil, Player, nil), http.StatusOK)
			}},

		// duplicates.feature
		Def{Name: "a registration with the same name and date of birth as a player flags both as suspected duplicates and opens a fraud case with the identity signal", Requirement: "008/FR-036",
			Run: func(t testing.TB, s *Session) {
				same := map[string]any{"firstName": "Grace", "lastName": "Hopper-" + s.ID("surname"), "dateOfBirth": "1985-12-09"}
				s.Want(s.Register("register the first player", "", same, true), http.StatusCreated)
				s.Want(s.Register("register a second with the same name and date of birth", "other", same, false), http.StatusCreated)
				openCase(s)
			}},
		Def{Name: "a registration with the same device fingerprint as a player flags both and opens a fraud case with the device signal", Requirement: "008/FR-036",
			Run: func(t testing.TB, s *Session) {
				device := map[string]any{"deviceFingerprint": s.ID("device")}
				s.Want(s.Register("register the first player", "", device, true), http.StatusCreated)
				s.Want(s.Register("register a second on the same device", "other", device, false), http.StatusCreated)
				openCase(s)
			}},
		Def{Name: "dismissing a fraud case clears the flag of every player it links", Requirement: "008/FR-037",
			Run: func(t testing.TB, s *Session) {
				device := map[string]any{"deviceFingerprint": s.ID("device")}
				s.Want(s.Register("register the first player", "", device, true), http.StatusCreated)
				s.Want(s.Register("register a second on the same device", "other", device, true), http.StatusCreated)
				id := openCase(s)
				s.Want(s.Casino("dismiss the case", http.MethodPatch, s.Backoffice()+"/fraud-cases/"+url.PathEscape(id)+"/dismiss", map[string]any{"notes": "siblings sharing a laptop"}, Operator, nil), http.StatusOK)
				s.Want(s.Casino("list the dismissed cases", http.MethodGet, s.Backoffice()+"/fraud-cases?status=DISMISSED", nil, Operator, nil), http.StatusOK)
			}},
		Def{Name: "reviewing a fraud case marks it reviewed with the operator and the time", Requirement: "008/FR-037",
			Run: func(t testing.TB, s *Session) {
				device := map[string]any{"deviceFingerprint": s.ID("device")}
				s.Want(s.Register("register the first player", "", device, true), http.StatusCreated)
				s.Want(s.Register("register a second on the same device", "other", device, true), http.StatusCreated)
				id := openCase(s)
				s.Want(s.Casino("review the case", http.MethodPatch, s.Backoffice()+"/fraud-cases/"+url.PathEscape(id)+"/review", nil, Operator, nil), http.StatusOK)
				s.Want(s.Casino("list the reviewed cases", http.MethodGet, s.Backoffice()+"/fraud-cases?status=REVIEWED", nil, Operator, nil), http.StatusOK)
			}},
		Def{Name: "a registration without names, date of birth or device fingerprint compares nothing", Requirement: "008/FR-036",
			Run: func(t testing.TB, s *Session) {
				s.Want(s.Register("register the first player", "", nil, true), http.StatusCreated)
				s.Want(s.Register("register a second with nothing to compare", "other", nil, false), http.StatusCreated)
				r := s.Want(s.Casino("list the open cases", http.MethodGet, s.Backoffice()+"/fraud-cases?status=OPEN", nil, Operator, nil), http.StatusOK)
				if id := FraudCase(r, s.Value("otherPlayerId")); id != "" {
					t.Errorf("a case %s links a player with nothing to compare", id)
				}
			}},

		// the operator's routes no feature scenario names
		Def{Name: ExtraMessage, Requirement: "008/FR-030",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Casino("send the player a message", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/messages",
					map[string]any{"subjectTemplate": "Your account", "bodyTemplate": "Hello {{first_name}}", "category": "service"}, Operator, nil), http.StatusCreated)
				s.Want(s.Casino("the player reads their inbox", http.MethodGet, s.Players()+"/"+s.Value("playerId")+"/messages", nil, Player, nil), http.StatusOK)
				s.Want(s.Casino("an operator reads the player's messages", http.MethodGet, s.Backoffice()+"/players/"+s.Value("playerId")+"/messages", nil, Operator, nil), http.StatusOK)
			}},
		Def{Name: ExtraMergeTag, Requirement: "008/FR-030",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				s.Want(s.Casino("read the merge tags", http.MethodGet, "/v1/casino/brands/"+s.Brand()+"/merge-tags", nil, Operator, nil), http.StatusOK)
				r := s.Casino("send a message naming an unknown merge tag", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/messages",
					map[string]any{"subjectTemplate": "Hi", "bodyTemplate": "Hello {{no_such_tag}}", "category": "service"}, Operator, nil)
				s.WantField(s.Want(r, http.StatusUnprocessableEntity), "error", "unknown_merge_tag")
			}},
		Def{Name: ExtraExport, Requirement: "008/FR-040",
			Run: func(t testing.TB, s *Session) {
				s.Active("", nil)
				r := s.Casino("prepare an activity export", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/activity-export", map[string]any{}, Operator, nil)
				if s.Want(r, http.StatusAccepted).Status == http.StatusAccepted {
					s.Grab("exportId", "$.exportId", r.Str("exportId"))
				}
				s.Observe("the export is ready", "casino", http.MethodGet, s.Backoffice()+"/exports/"+s.Value("exportId"), Operator,
					func(r Response) bool { return r.Status == http.StatusOK && r.Str("status") != "pending" })
				s.Want(s.Casino("read the export", http.MethodGet, s.Backoffice()+"/exports/"+s.Value("exportId"), nil, Operator, nil), http.StatusOK)
			}},
	)
	return defs
}

// status is a restricted status: the example's words, the original's value, and how the run applies it.
type status struct {
	example, status string
	apply           func(s *Session)
}

// restricted is the outlines' statuses, with `closed` when the outline names it.
func restricted(closed bool) []status {
	out := []status{
		{"suspended", "suspended", func(s *Session) {
			s.Want(s.Setup("suspend the player", "casino", http.MethodPatch, s.Operator()+"/suspend", map[string]any{"reason": "chargeback under review"}, Operator), http.StatusOK)
		}},
		{"self-excluded", "self_excluded", func(s *Session) { s.SetStatus("self_excluded") }},
		{"blocked", "blocked", func(s *Session) { s.SetStatus("blocked") }},
	}
	if closed {
		out = append(out, status{"closed", "closed", func(s *Session) {
			s.Want(s.Setup("close the account", "casino", http.MethodPost, s.Backoffice()+"/players/"+s.Value("playerId")+"/close", map[string]any{}, Operator), http.StatusOK)
		}})
	}
	return out
}

// secondAddress adds a second address and follows its link; it answers the address.
func secondAddress(s *Session) string {
	second := s.Email("secondEmail")
	s.Want(s.Casino("add a second address", http.MethodPost, s.Players()+"/emails", map[string]any{"address": second}, Player, nil), http.StatusAccepted)
	token := s.SecondAddressToken(second)
	s.Remember("secondLink", token)
	s.Want(s.Casino("follow the second address's link", http.MethodPost, s.Players()+"/emails/verify",
		map[string]any{"token": token, "playerId": s.Value("playerId")}, NoAuth, nil), http.StatusOK)
	return second
}

// cookie is a cookie decision for the scenario's device, without `missing` when one is named.
func cookie(s *Session, missing string) map[string]any {
	b := map[string]any{
		"deviceId":         s.ID("device"),
		"analyticsConsent": true,
		"marketingConsent": false,
		"version":          "2026-10",
		"timestamp":        "2026-10-25T10:00:00Z",
	}
	delete(b, missing)
	return b
}

// openCase waits for the open case linking the second player and answers its id.
func openCase(s *Session) string {
	other := s.Value("otherPlayerId")
	r := s.Observe("an open case links the second player", "casino", http.MethodGet, s.Backoffice()+"/fraud-cases?status=OPEN", Operator,
		func(r Response) bool { return FraudCase(r, other) != "" })
	id := FraudCase(r, other)
	s.Remember("caseId", id)
	return id
}
