package publicapidriver

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/eitheror-io/ankka-casino/tests/original/recorder"
)

// The partner side: the four partner clients the recordings act as, the grants the original must hold
// for them, and the Keycloak calls that make a client and fetch its token. None of these calls is
// recorded: they carry Keycloak's admin token and the clients' secrets.

// The two brands, as the original's kind stack seeds them (deploy/kind/casino-values.yaml `brands:`).
const (
	BrandA = "spinvibe"
	BrandB = "goldvibe"
)

// Realm is where the original's partner clients live (bundle/helm/values.yaml's eitheror-realm.json).
const Realm = "eitheror"

// IdentifyingFields are the original's entitlement names, every one (mappers/player.ts IDENTIFYING_FIELDS).
var IdentifyingFields = []string{
	"email", "firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "stateProv", "postalCode",
}

// Partner is one partner client and the grant it must hold.
type Partner struct {
	Auth              Auth
	ClientID          string // the Keycloak client id, which is the grant's `id` (partnerToken.ts reads `azp`)
	Letter            string // "A" or "B"
	Fields            []string
	Agreement         bool // a data-processing agreement and a lawful basis; without both no field is served
	RequestsPerMinute int
	// Shipped is whether the original's kind realm already defines the client. The other two are made
	// through Keycloak's admin API with the same mappers.
	Shipped bool
}

// Partners are the run's four clients. `partner-local` and `partner-goldvibe-local` are the kind
// realm's; `bareToken`'s and `quotaToken`'s are made by the run: the identifying-fields recording reads
// one brand A player under a full and an empty grant, and the quota recording must not share a window
// with the other recordings.
var Partners = []Partner{
	{Full, "partner-local", "A", IdentifyingFields, true, 600, true},
	{Bare, "partner-spinvibe-bare-local", "A", nil, false, 600, false},
	{Other, "partner-goldvibe-local", "B", nil, false, 600, true},
	{Quota, "partner-spinvibe-quota-local", "A", nil, false, 5, false},
}

// PartnerOf is the partner client an Auth names.
func PartnerOf(auth Auth) (Partner, bool) {
	for _, p := range Partners {
		if p.Auth == auth {
			return p, true
		}
	}
	return Partner{}, false
}

// GrantsValues is the Helm values overlay that gives the original these four grants: its grants are
// `casinoPublicApi.partners` in the chart's values, rendered into a ConfigMap mounted at
// `/etc/casino-public-api/grants.json` and re-read every 60 seconds (grants/entitlements.ts). The
// original has no route that writes a grant. JSON is YAML, so `helm upgrade -f` takes the file as it is.
// It replaces the kind overlay's two dev grants (which gave partner-local three fields and
// partner-goldvibe-local one) with the run's.
func GrantsValues(brandA, brandB string) []byte {
	type processing struct {
		AgreementRef string `json:"agreementRef"`
		LawfulBasis  string `json:"lawfulBasis"`
		SignedOn     string `json:"signedOn"`
	}
	type quota struct {
		RequestsPerMinute int `json:"requestsPerMinute"`
	}
	type grant struct {
		ID                string      `json:"id"`
		BrandID           string      `json:"brandId"`
		ScopeLevel        string      `json:"scopeLevel"`
		Enabled           bool        `json:"enabled"`
		IdentifyingFields []string    `json:"identifyingFields"`
		DataProcessing    *processing `json:"dataProcessing,omitempty"`
		Quota             quota       `json:"quota"`
	}
	var grants []grant
	for _, p := range Partners {
		g := grant{ID: p.ClientID, BrandID: brandA, ScopeLevel: "sub_brand", Enabled: true,
			IdentifyingFields: append([]string{}, p.Fields...), Quota: quota{p.RequestsPerMinute}}
		if p.Letter == "B" {
			g.BrandID = brandB
		}
		if p.Agreement {
			g.DataProcessing = &processing{"DPA-PUBLIC-API-DRIVER", "contract", "2026-11-12"}
		}
		grants = append(grants, g)
	}
	b, _ := json.MarshalIndent(map[string]any{"casinoPublicApi": map[string]any{"partners": grants}}, "", "  ")
	return append(b, '\n')
}

// ClientRepresentation is a partner client as the kind realm defines `partner-local`: confidential,
// client-credentials only, five-minute tokens, and the three hard-coded claims the original checks
// (eitheror_actor_type, eitheror_scope_level, eitheror_scope_id).
func ClientRepresentation(clientID, brand string) map[string]any {
	claim := func(name, value string) map[string]any {
		return map[string]any{
			"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-hardcoded-claim-mapper",
			"consentRequired": false,
			"config": map[string]string{
				"claim.value": value, "claim.name": name, "jsonType.label": "String",
				"userinfo.token.claim": "true", "id.token.claim": "true", "access.token.claim": "true",
			},
		}
	}
	return map[string]any{
		"clientId": clientID, "enabled": true, "publicClient": false, "standardFlowEnabled": false,
		"directAccessGrantsEnabled": false, "serviceAccountsEnabled": true, "clientAuthenticatorType": "client-secret",
		"attributes": map[string]string{"access.token.lifespan": "300"},
		"protocolMappers": []map[string]any{
			claim("eitheror_scope_level", "sub_brand"),
			claim("eitheror_scope_id", BrandScopeID(brand)),
			claim("eitheror_actor_type", "partner"),
		},
	}
}

// Keycloak is the original's Keycloak, administered as the suite's partnerapi.Token does: the master
// realm's admin by password, then the eitheror realm's admin API.
type Keycloak struct {
	Client        *http.Client
	URL           string
	AdminPassword string // EITHEROR_KEYCLOAK_ADMIN_PASSWORD; never written
}

func (k Keycloak) do(method, path, bearer string, form url.Values, body any, out any) (int, error) {
	u, err := url.Parse(strings.TrimSuffix(k.URL, "/") + path)
	if err != nil {
		return 0, err
	}
	if !recorder.Local(u.Host) {
		return 0, fmt.Errorf("refusing %s: nothing may leave the machine while the original is recorded", u.Host)
	}
	var req *http.Request
	switch {
	case form != nil:
		req, _ = http.NewRequest(method, u.String(), strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	case body != nil:
		b, _ := json.Marshal(body)
		req, _ = http.NewRequest(method, u.String(), strings.NewReader(string(b)))
		req.Header.Set("Content-Type", "application/json")
	default:
		req, _ = http.NewRequest(method, u.String(), nil)
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	resp, err := k.Client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if out != nil && resp.StatusCode/100 == 2 && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return resp.StatusCode, fmt.Errorf("%s %s: %v", method, path, err)
		}
	}
	return resp.StatusCode, nil
}

func (k Keycloak) admin() (string, error) {
	var out struct {
		AccessToken string `json:"access_token"`
	}
	status, err := k.do(http.MethodPost, "/realms/master/protocol/openid-connect/token", "",
		url.Values{"client_id": {"admin-cli"}, "grant_type": {"password"}, "username": {"admin"}, "password": {k.AdminPassword}}, nil, &out)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK || out.AccessToken == "" {
		return "", fmt.Errorf("Keycloak's master realm answered %d for its admin", status)
	}
	return out.AccessToken, nil
}

func (k Keycloak) find(admin, clientID string) (string, error) {
	var clients []struct {
		ID string `json:"id"`
	}
	status, err := k.do(http.MethodGet, "/admin/realms/"+Realm+"/clients?clientId="+url.QueryEscape(clientID), admin, nil, nil, &clients)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", fmt.Errorf("Keycloak answered %d listing %s", status, clientID)
	}
	if len(clients) == 0 {
		return "", nil
	}
	return clients[0].ID, nil
}

// EnsureClient makes the partner's client when the realm does not hold it; a shipped client must
// already be there.
func (k Keycloak) EnsureClient(p Partner, brand string) error {
	admin, err := k.admin()
	if err != nil {
		return err
	}
	id, err := k.find(admin, p.ClientID)
	if err != nil || id != "" {
		return err
	}
	if p.Shipped {
		return fmt.Errorf("the %s realm has no client %s: the original's kind realm defines it", Realm, p.ClientID)
	}
	status, err := k.do(http.MethodPost, "/admin/realms/"+Realm+"/clients", admin, nil, ClientRepresentation(p.ClientID, brand), nil)
	if err != nil {
		return err
	}
	if status != http.StatusCreated {
		return fmt.Errorf("Keycloak answered %d creating %s", status, p.ClientID)
	}
	return nil
}

// ClientToken is a client-credentials token for a partner client, its secret read through the admin
// API as the suite's partnerapi.Token reads it (kind generates the secrets at realm import).
func (k Keycloak) ClientToken(clientID string) (string, error) {
	admin, err := k.admin()
	if err != nil {
		return "", err
	}
	id, err := k.find(admin, clientID)
	if err != nil {
		return "", err
	}
	if id == "" {
		return "", fmt.Errorf("the %s realm has no client %s", Realm, clientID)
	}
	var secret struct {
		Value string `json:"value"`
	}
	if status, err := k.do(http.MethodGet, "/admin/realms/"+Realm+"/clients/"+id+"/client-secret", admin, nil, nil, &secret); err != nil || status != http.StatusOK {
		return "", fmt.Errorf("Keycloak answered %d for %s's secret: %v", status, clientID, err)
	}
	var tok struct {
		AccessToken string `json:"access_token"`
	}
	status, err := k.do(http.MethodPost, "/realms/"+Realm+"/protocol/openid-connect/token", "",
		url.Values{"grant_type": {"client_credentials"}, "client_id": {clientID}, "client_secret": {secret.Value}}, nil, &tok)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK || tok.AccessToken == "" {
		return "", fmt.Errorf("realm %s answered %d for %s", Realm, status, clientID)
	}
	return tok.AccessToken, nil
}

// PasswordToken fetches a token from a realm by the password grant: how the run obtains the operator's
// sign-in, which the steps then carry as `{operatorToken}`.
func (k Keycloak) PasswordToken(realm, clientID, user, password string) (string, error) {
	var out struct {
		AccessToken string `json:"access_token"`
	}
	status, err := k.do(http.MethodPost, "/realms/"+realm+"/protocol/openid-connect/token", "",
		url.Values{"grant_type": {"password"}, "client_id": {clientID}, "username": {user}, "password": {password}}, nil, &out)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK || out.AccessToken == "" {
		return "", fmt.Errorf("realm %s answered %d for %s", realm, status, user)
	}
	return out.AccessToken, nil
}
