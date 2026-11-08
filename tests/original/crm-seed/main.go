// Command crm-seed points the recording brand's Customer.io configuration at crm-stub before the
// original is recorded (spec 014 research R2): the stub's site id and keys, and a transactional id
// for every trigger the original knows, so every dispatch also sends. The configuration it found is
// written to -saved first, and `-restore` puts that back after the run: its triggers, region and
// sender; its keys come back masked, so the original keeps the stub's (empty keys on a fresh kind
// stack stay empty only if the brand had none to begin with). The operator signs in by the
// password grant outside any recording; the password comes from TEST_OPERATOR_PASSWORD.
//
//	go run ./original/crm-seed -casino-url http://localhost:8091 -keycloak-url http://localhost:8180 \
//	  -brand spinvibe -operator-client casino-backoffice -saved /tmp/crm-config-spinvibe.json
//	go run ./original/crm-seed … -saved /tmp/crm-config-spinvibe.json -restore
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
)

// Stub is the configuration crm-stub accepts (tests/stubs/crm-stub/README.md).
type Stub struct{ SiteID, TrackKey, AppKey string }

// Seeded is the PUT body: the stub's credentials, the found configuration's region, signing key and
// sender, and `stub-<trigger>` as the transactional id of every trigger the found configuration lists.
// The signing key the GET answered masked is sent back masked, which the original keeps as stored.
func Seeded(found map[string]any, stub Stub) map[string]any {
	triggers := map[string]any{}
	if t, ok := found["triggers"].(map[string]any); ok {
		for k := range t {
			triggers[k] = map[string]any{"transactionalId": "stub-" + k}
		}
	}
	str := func(k, def string) string {
		if v, ok := found[k].(string); ok && v != "" {
			return v
		}
		return def
	}
	return map[string]any{
		"siteId":            stub.SiteID,
		"trackApiKey":       stub.TrackKey,
		"appApiKey":         stub.AppKey,
		"region":            str("region", "us"),
		"webhookSigningKey": str("webhookSigningKey", ""),
		"fromEmail":         str("fromEmail", "recording@example.com"),
		"triggers":          triggers,
	}
}

// Triggers are a configuration's trigger names, sorted.
func Triggers(cfg map[string]any) []string {
	t, _ := cfg["triggers"].(map[string]any)
	names := make([]string, 0, len(t))
	for k := range t {
		names = append(names, k)
	}
	sort.Strings(names)
	return names
}

func main() {
	casino := flag.String("casino-url", "http://localhost:8091", "the original casino-service")
	keycloak := flag.String("keycloak-url", "http://localhost:8180", "the original's Keycloak")
	brand := flag.String("brand", "spinvibe", "the recording brand")
	realm := flag.String("operator-realm", "eitheror", "the realm the backoffice signs operators in to")
	clientID := flag.String("operator-client", "casino-backoffice", "the backoffice's client in that realm")
	user := flag.String("operator", envOr("TEST_OPERATOR_EMAIL", "dev-operator"), "the operator (brand_admin at the brand)")
	saved := flag.String("saved", "", "where the found configuration is written, or read back with -restore")
	restore := flag.Bool("restore", false, "put the saved configuration back")
	siteID := flag.String("site-id", "stub-site", "crm-stub's -site-id")
	trackKey := flag.String("track-key", "stub-track-key", "crm-stub's -track-key")
	appKey := flag.String("app-key", "stub-app-key", "crm-stub's -app-key")
	flag.Parse()
	if *saved == "" {
		fail("crm-seed needs -saved")
	}
	token, err := passwordToken(*keycloak, *realm, *clientID, *user, os.Getenv("TEST_OPERATOR_PASSWORD"))
	check(err)
	route := strings.TrimSuffix(*casino, "/") + "/v1/casino/crm-config/" + url.PathEscape(*brand)
	if *restore {
		b, err := os.ReadFile(*saved)
		check(err)
		var found map[string]any
		check(json.Unmarshal(b, &found))
		check(put(route, token, found))
		fmt.Printf("%s's CRM configuration put back from %s\n", *brand, *saved)
		return
	}
	found, err := get(route, token)
	check(err)
	b, _ := json.MarshalIndent(found, "", "  ")
	check(os.WriteFile(*saved, append(b, '\n'), 0o600))
	seeded := Seeded(found, Stub{*siteID, *trackKey, *appKey})
	check(put(route, token, seeded))
	fmt.Printf("%s's CRM configuration points at crm-stub with %d triggers: %s\n", *brand, len(Triggers(seeded)), strings.Join(Triggers(seeded), ", "))
}

func get(route, token string) (map[string]any, error) {
	req, _ := http.NewRequest(http.MethodGet, route, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s answered %d: %s", route, resp.StatusCode, raw)
	}
	var out map[string]any
	return out, json.Unmarshal(raw, &out)
}

func put(route, token string, body map[string]any) error {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPut, route, bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("PUT %s answered %d: %s", route, resp.StatusCode, raw)
	}
	return nil
}

func passwordToken(keycloak, realm, clientID, user, password string) (string, error) {
	u := strings.TrimSuffix(keycloak, "/") + "/realms/" + realm + "/protocol/openid-connect/token"
	form := url.Values{"grant_type": {"password"}, "client_id": {clientID}, "username": {user}, "password": {password}}
	resp, err := http.PostForm(u, form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
	}
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || json.Unmarshal(raw, &out) != nil || out.AccessToken == "" {
		return "", fmt.Errorf("realm %s answered %d for %s", realm, resp.StatusCode, user)
	}
	return out.AccessToken, nil
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func check(err error) {
	if err != nil {
		fail(err.Error())
	}
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, msg)
	os.Exit(1)
}
