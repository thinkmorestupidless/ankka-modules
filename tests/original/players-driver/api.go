package playersdriver

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// The run's passwords: one the realm accepts, a second for a password change, one below its policy.
// They are made up, the same on every run, so a replay sends them as they are.
const (
	Password     = "Players-Driver-1!"
	NewPassword  = "Players-Driver-2!"
	WeakPassword = "a"
	// GameID is any game: the original's launch hands it to the fake ST8, which serves every id.
	GameID = "players-driver-game"
	// NetworkAddress is a documentation-range address (RFC 5737), sent as the edge forwards one.
	NetworkAddress = "192.0.2.44"
)

// key names a player's value: `email`, `playerId`, `token` for the scenario's player, and
// `otherEmail`, `otherPlayerId`, `otherToken` for another (`who` = "other").
func key(who, name string) string {
	if who == "" {
		return name
	}
	return who + strings.ToUpper(name[:1]) + name[1:]
}

// Players is the player routes' prefix; Sessions the game sessions'; Operator the operator routes
// BackofficePlayerRoutes serves; Backoffice the ones PlayerProfileRoutes and FraudCaseRoutes serve.
func (s *Session) Players() string  { return "/v1/casino/players/" + s.Brand() }
func (s *Session) Sessions() string { return "/v1/casino/sessions/" + s.Brand() }
func (s *Session) Operator() string {
	return "/v1/casino/" + s.Brand() + "/players/" + s.Value("playerId")
}
func (s *Session) Backoffice() string { return "/v1/casino/backoffice/" + s.Brand() }

// Registration is a registration as the frontend sends it, for `who`'s address, with `change`
// applied: a field set, or deleted when its value is nil.
func (s *Session) Registration(who string, change map[string]any) map[string]any {
	b := map[string]any{
		"email":    s.Email(key(who, "email")),
		"password": Password,
		"currency": "EUR",
		"country":  "GB",
		"language": "en",
	}
	for k, v := range change {
		if v == nil {
			delete(b, k)
		} else {
			b[k] = v
		}
	}
	return b
}

// Register posts a registration and captures the player id as `who`'s, as a step or a setup step.
func (s *Session) Register(name, who string, change map[string]any, setup bool) Response {
	path := s.Players() + "/register"
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, path, s.Registration(who, change), NoAuth)
	} else {
		r = s.Casino(name, http.MethodPost, path, s.Registration(who, change), NoAuth, nil)
	}
	if r.Status == http.StatusCreated {
		s.Grab(key(who, "playerId"), "$.playerId", r.Str("playerId"))
	}
	return r
}

// Link is `who`'s verification link's token, minted as the original mints it; recorded as its name.
func (s *Session) Link(who string) map[string]any {
	token := VerificationToken(s.cfg.SigningSecret, s.Brand(), s.Value(key(who, "playerId")), s.Value(key(who, "email")),
		s.cfg.Now().Add(24*time.Hour))
	s.Remember(key(who, "link"), token)
	return map[string]any{"token": token}
}

// Login signs `who` in with `password` and captures the sign-in and its refresh.
func (s *Session) Login(name, who, password string, setup bool) Response {
	body := map[string]any{"email": s.Value(key(who, "email")), "password": password}
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, s.Players()+"/login", body, NoAuth)
	} else {
		r = s.Casino(name, http.MethodPost, s.Players()+"/login", body, NoAuth, nil)
	}
	if r.Status == http.StatusOK {
		s.Grab(key(who, "token"), "$.token", r.Str("token"))
		s.Remember(key(who, "refreshToken"), r.Str("refreshToken"))
	}
	return r
}

// Active is `who` registered (with `change`), verified and signed in, all as setup.
func (s *Session) Active(who string, change map[string]any) {
	s.t.Helper()
	s.Want(s.Register("register "+label(who), who, change, true), http.StatusCreated)
	s.Want(s.Setup("follow "+label(who)+"'s verification link", "casino", http.MethodPost, s.Players()+"/verify-email", s.Link(who), NoAuth), http.StatusOK)
	s.Want(s.Login("sign "+label(who)+" in", who, Password, true), http.StatusOK)
}

func label(who string) string {
	if who == "" {
		return "the player"
	}
	return "the " + who + " player"
}

// Launch opens a game session for `who` at launch, as the frontend does, and captures its id as `capture`.
func (s *Session) Launch(name, who, capture string, setup bool) Response {
	body := map[string]any{"playerId": s.Value(key(who, "playerId")), "gameId": GameID, "currency": "EUR"}
	auth := Auth(key(who, "token"))
	var r Response
	if setup {
		r = s.Setup(name, "casino", http.MethodPost, s.Sessions()+"/launch", body, auth)
	} else {
		r = s.Casino(name, http.MethodPost, s.Sessions()+"/launch", body, auth, nil)
	}
	if r.Status == http.StatusOK && capture != "" {
		s.Grab(capture, "$.sessionId", r.Str("sessionId"))
	}
	return r
}

// SetStatus writes the player's status where the original keeps it (`account_status` on the player
// twin), for a status the original sets only by migration or through another service.
func (s *Session) SetStatus(status string) {
	s.Want(s.Setup("the player's status is "+status, "substrate", http.MethodPost,
		"/twins/casino-"+s.Brand()+"/"+s.Value("playerId"), map[string]any{"account_status": status}, NoAuth), http.StatusCreated)
}

// Twin reads the player twin's property `prop` (as a string), recording the read as setup.
func (s *Session) Twin(name, prop string) string {
	r := s.Setup(name, "substrate", http.MethodGet, "/twins/casino-"+s.Brand()+"/"+s.Value("playerId"), nil, NoAuth)
	v := r.Str(prop)
	if v == "" {
		if props, ok := r.Body["properties"].(map[string]any); ok {
			if p, ok := props[prop].(string); ok {
				v = p
			}
		}
	}
	return v
}

// SecondAddressToken is the verification token the original keeps on the player for `address`
// (`player_email_list`, PlayerEmailAddress.verificationToken).
func (s *Session) SecondAddressToken(address string) string {
	raw := s.Twin("read the second address's verification token", "player_email_list")
	var list []struct {
		Address           string `json:"address"`
		VerificationToken string `json:"verificationToken"`
	}
	_ = json.Unmarshal([]byte(raw), &list)
	for _, e := range list {
		if strings.EqualFold(e.Address, address) {
			return e.VerificationToken
		}
	}
	s.t.Errorf("no verification token for the second address in %q", raw)
	return ""
}

// RealmUser is `who`'s user id in the brand's realm, read through the admin API as setup.
func (s *Session) RealmUser(who string) string {
	r := s.Setup("find "+label(who)+" in the realm", "keycloak", http.MethodGet,
		"/admin/realms/casino-"+s.Brand()+"/users?exact=true&email="+url.QueryEscape(s.Value(key(who, "email"))), nil, Admin)
	var users []struct {
		ID string `json:"id"`
	}
	_ = json.Unmarshal(r.Raw, &users)
	if len(users) == 0 {
		s.t.Errorf("no realm user for %s: %s", label(who), string(r.Raw))
		return ""
	}
	s.Remember(key(who, "realmUser"), users[0].ID)
	return users[0].ID
}

// FraudCase finds the open case linking `playerId` in a fraud-case listing, or "".
func FraudCase(r Response, playerID string) string {
	cases, _ := r.Body["cases"].([]any)
	for _, c := range cases {
		m, _ := c.(map[string]any)
		ref, _ := m["playerRef"].(string)
		linked, _ := m["linkedPlayerRef"].(string)
		if strings.HasSuffix(ref, playerID) || strings.HasSuffix(linked, playerID) {
			id, _ := m["caseId"].(string)
			return id
		}
	}
	return ""
}
