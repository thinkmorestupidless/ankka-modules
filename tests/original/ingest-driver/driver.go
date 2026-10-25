// Package ingestdriver drives the original's merchant-service at its public cashier edge, the routes
// spec 006's `ingest` takes over, and records each scenario of features/payments/cashier.feature the
// original can show as one recording (specs/006-ingest/contracts/recording.md).
//
// The original served the edge and the cashier in one process, so the Go suite reaches the edge only on
// its happy path (scenarios 12 to 14, whose recordings tests/port/ingest copies). What it never sends is
// what this driver sends: no key, a key nobody holds, a disabled key, each missing or malformed field,
// repeats under one request id, a ticket nobody was given, another sub-brand's key on a read, the key's
// header spelt otherwise. Each scenario seeds its own sub-brands through the original's seed routes, as
// merchant-driver does, and the seed's answer is where a replay finds the raw key. What the original has
// no route for (a disabled key, a sub-brand with no tree or one that matches nothing) is written to
// substrate as merchant-service writes it. Both are `setup:` steps a replay does not compare.
//
// The machinery needs no running original, so `go test ./original/ingest-driver/...` checks it and the
// scenario names against a stub; the run itself is tagged `e2e`.
package ingestdriver

import (
	"encoding/json"
	"net/http"
	"testing"

	md "github.com/eitheror-io/ankka-casino/tests/original/merchant-driver"
)

type (
	Session  = md.Session
	Response = md.Response
)

// Def is one recording: a feature scenario, or one example of an outline.
type Def struct {
	Name        string // as cashier.feature names the scenario
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

// Body is a create as a brand's cashier page sends it, under request id `txn` (none when empty), with
// `change` applied: a field set, or deleted when its value is nil.
func Body(s *Session, txn string, change map[string]any) map[string]any {
	b := map[string]any{
		"amountCents":   5000,
		"currency":      "EUR",
		"redirectUrl":   "https://merchant.example/return",
		"country":       "GB",
		"customerId":    s.Customer(true),
		"customerEmail": "player@example.com",
	}
	if txn != "" {
		b["transactionId"] = txn
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

// Create posts a create and, when the original answers a session, captures its ticket as `capture`.
func Create(s *Session, name, capture string, body map[string]any, auth md.Auth, headers map[string]string) Response {
	r := s.Merchant(name, http.MethodPost, "/v1/cashier/sessions", body, auth, headers)
	if r.Status == 200 && capture != "" {
		s.Grab(capture, "$.session_token", r.Str("session_token"))
	}
	return r
}

// Other seeds a second sub-brand with its own key, as `otherSubBrandId` and `otherMerchantKey`.
func Other(s *Session) {
	brand := s.ID("otherBrand")
	r := s.Setup("seed another hierarchy", "merchant", http.MethodPost, "/internal/v1/seed/hierarchy/"+brand, nil, md.Internal)
	s.Want(r, 200)
	s.Grab("otherSubBrandId", "$.subBrandId", r.Str("subBrandId"))
	r = s.Setup("seed another merchant: key, tree to SIM, SIM credential", "merchant", http.MethodPost,
		"/internal/v1/seed/merchants/"+brand, map[string]any{}, md.Internal)
	s.Want(r, 200)
	s.Grab("otherMerchantKey", "$.merchantKey", r.Str("merchantKey"))
}

// twin names the sub-brand's substrate id, which its keys and its routing tree are kept under.
func twin(s *Session) string {
	id := md.TwinID(s.Value("subBrandId"))
	s.Remember("subBrandTwin", id)
	return id
}

// DisableKey marks the sub-brand's keys inactive in the twin the original reads them from
// (`eitheror-merchant-keys`, MerchantServiceClient.saveKeys): the original has no route that does it.
func DisableKey(t testing.TB, s *Session) {
	t.Helper()
	path := "/twins/eitheror-merchant-keys/" + twin(s)
	r := s.Setup("read the sub-brand's keys", "substrate", http.MethodGet, path, nil, md.NoAuth)
	var keys []map[string]any
	if err := json.Unmarshal([]byte(r.Str("keys")), &keys); err != nil || len(keys) == 0 {
		t.Fatalf("the sub-brand has no keys to disable: %s", string(r.Raw))
	}
	for _, k := range keys {
		k["active"] = false
		for _, field := range []string{"keyId", "keyHash", "createdAt"} {
			if v, ok := k[field].(string); ok {
				s.Remember(field, v)
			}
		}
	}
	b, _ := json.Marshal(keys)
	s.Want(s.Setup("disable the key", "substrate", http.MethodPost, path, map[string]any{"keys": string(b)}, md.NoAuth), 201)
}

// Tree replaces the sub-brand's routing tree twin (`eitheror-merchant-routing`, saveRoutingTree) with
// `tree`, the stored `{draft, liveVersion, versions}` shape.
func Tree(s *Session, name string, tree map[string]any) {
	b, _ := json.Marshal(tree)
	s.Want(s.Setup(name, "substrate", http.MethodPost, "/twins/eitheror-merchant-routing/"+twin(s),
		map[string]any{"sub_brand_id": s.Value("subBrandId"), "routingTree": string(b)}, md.NoAuth), 201)
}

// NoTree leaves the sub-brand no deployed version, so resolution falls to its company and holding,
// which the seed gives none.
func NoTree(s *Session) {
	Tree(s, "the sub-brand's tree has no deployed version", map[string]any{"draft": nil, "liveVersion": nil, "versions": []any{}})
}

// NoMatch deploys a tree whose one branch takes only France, so a GB session matches nothing.
func NoMatch(s *Session) {
	Tree(s, "the sub-brand's tree routes only France", map[string]any{
		"draft": nil, "liveVersion": 1, "liveDeployedAt": "2026-01-01T00:00:00Z",
		"versions": []any{map[string]any{
			"version": 1, "deployedAt": "2026-01-01T00:00:00Z",
			"tree": map[string]any{"rootNodeId": "root", "nodes": map[string]any{
				"root": map[string]any{"nodeId": "root", "terminals": nil, "branches": []any{
					map[string]any{"conditions": []any{map[string]any{"conditionType": "COUNTRY", "operator": "EQ", "value": "FR"}}, "nextNodeId": "fr"},
				}},
				"fr": map[string]any{"nodeId": "fr", "branches": nil, "terminals": []any{"SIM"}},
			}},
		}},
	})
}

// ReadAs reads the payment with a key's header: `{merchantKey}`'s own, or another's by its name.
func ReadAs(s *Session, name, key string) Response {
	return s.Merchant(name, http.MethodGet, "/v1/payments/"+s.Value("payment"), nil, md.NoAuth, map[string]string{"merchantKey": s.Value(key)})
}
