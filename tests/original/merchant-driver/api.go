package merchantdriver

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

// The original's cashier, one helper per route the scenarios use (CashierRoutes.scala). Amounts go in
// as the original takes them: `amountCents` as a number on the session. Every id the original mints is
// captured from the answer that gave it, so a replay fills it from the rebuild's.

// SubBrand seeds a fresh brand's holding, company and sub-brand, then a merchant key, a deployed
// catch-all tree and the simulator credential `SIM` (DevSeedRoutes: seed/hierarchy, seed/merchants).
// Each scenario takes its own brand id, so no scenario shares a sub-brand with another.
func (s *Session) SubBrand() {
	brand := s.ID("brand")
	r := s.Setup("seed hierarchy", "merchant", http.MethodPost, "/internal/v1/seed/hierarchy/"+brand, nil, Internal)
	s.Want(r, 200)
	s.Grab("subBrandId", "$.subBrandId", r.Str("subBrandId"))
	r = s.Setup("seed merchant: key, tree to SIM, SIM credential", "merchant", http.MethodPost,
		"/internal/v1/seed/merchants/"+brand, map[string]any{}, Internal)
	s.Want(r, 200)
	s.Grab("merchantKey", "$.merchantKey", r.Str("merchantKey"))
}

// WebhookSecret gives the sub-brand's simulator credential a webhookSecret, as an operator's
// credential update would, by writing the credential twin the original reads (fetchPspCredentialTwin).
func (s *Session) WebhookSecret(secret string) {
	s.remember("webhookSecret", secret)
	index := NameUUID("eitheror-psp-cred-index." + s.ids["subBrandId"])
	r := s.Setup("read the sub-brand's credential index", "substrate", http.MethodGet, "/twins/eitheror-psp-cred-index/"+index, nil, NoAuth)
	ids := r.Str("ids")
	var first string
	for _, id := range splitIDs(ids) {
		first = id
		break
	}
	if first == "" {
		s.t.Fatalf("the sub-brand has no credential to give a webhook secret: %s", string(r.Raw))
	}
	s.remember("credentialId", first)
	creds, _ := json.Marshal(map[string]string{"webhookSecret": secret})
	// The seed's credential ids are not UUIDs (`cfg_…`); the original's twinUrl names such a twin
	// nameUUIDFromBytes("merchant." + id) (MerchantServiceClient.twinUrl), and substrate takes only UUIDs.
	s.remember("credentialTwin", TwinID(first))
	s.Want(s.Setup("give the credential a webhook secret", "substrate", http.MethodPost, "/twins/eitheror-psp-credential/"+TwinID(first),
		map[string]any{"credentials": string(creds)}, NoAuth), 201) // substrate's twin POST always answers 201
}

func splitIDs(s string) []string {
	var out []string
	cur := ""
	for _, c := range s {
		if c == '|' {
			if cur != "" {
				out = append(out, cur)
			}
			cur = ""
		} else {
			cur += string(c)
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

// Customer is the session's customer id: `brand:player`, or a bare player for a sub-brand that names
// no brand (the original keys the wallet credit on the part before the colon).
func (s *Session) Customer(withBrand bool) string {
	player := s.ID("player")
	if !withBrand {
		return player
	}
	return s.ids["brand"] + ":" + player
}

// Open creates a cashier session under the sub-brand's key and captures its token.
func (s *Session) Open(amountCents int64, currency, customerID string) Response {
	r := s.Merchant("create cashier session", http.MethodPost, "/v1/cashier/sessions", map[string]any{
		"amountCents":   amountCents,
		"currency":      currency,
		"transactionId": s.ID("txn"),
		"redirectUrl":   "https://merchant.example/return",
		"country":       "GB",
		"customerId":    customerID,
		"customerEmail": "player@example.com",
	}, MerchantKey, nil)
	if r.Status == 200 {
		s.Grab("session", "$.session_token", r.Str("session_token"))
	}
	return r
}

// Submit submits the session with the terminal the player chose and captures the payment id.
func (s *Session) Submit(terminal string) Response {
	r := s.Merchant("submit", http.MethodPost, "/v1/cashier/sessions/"+s.ids["session"]+"/submit",
		map[string]any{"selectedTerminalEntityId": terminal}, NoAuth, nil)
	if r.Status == 200 && s.ids["payment"] == "" {
		s.Grab("payment", "$.paymentId", r.Str("paymentId"))
	}
	return r
}

// Deposit opens and submits a session of 5000 EUR minor units to the simulator terminal.
func (s *Session) Deposit(withBrand bool) {
	s.Want(s.Open(5000, "EUR", s.Customer(withBrand)), 200)
	s.Want(s.Submit("SIM"), 200)
}

// Read is the payment read the frontend polls.
func (s *Session) Read() Response {
	return s.Merchant("read payment", http.MethodGet, "/v1/payments/"+s.ids["payment"], nil, NoAuth, nil)
}

// Capture posts the gateway's capture; headers carry a provider's signature when the scenario signs.
func (s *Session) Capture(name string, body string, headers map[string]string) Response {
	return s.Merchant(name, http.MethodPost, "/v1/payments/"+s.ids["payment"]+"/capture", RawBody(body), Internal, headers)
}

// CaptureBody is a simulator capture's body, with variance fields when given.
func (s *Session) CaptureBody(extra map[string]string) string {
	body := map[string]any{"outcome": "success", "psp_transaction_id": s.ID("pspTxn")}
	for k, v := range extra {
		body[k] = v
	}
	b, _ := json.Marshal(body)
	return string(b)
}

// Signed is the signature headers for body under secret, stamped `age` seconds ago.
func (s *Session) Signed(secret, body string, age int64) map[string]string {
	return map[string]string{
		"X-PSP-Signature": Sign(secret, []byte(body)),
		"X-PSP-Timestamp": fmt.Sprintf("%d", s.cfg.Now().Unix()-age),
	}
}

// Fail posts the gateway's failure.
func (s *Session) Fail(body map[string]any) Response {
	return s.Merchant("fail", http.MethodPost, "/v1/payments/"+s.ids["payment"]+"/fail", body, Internal, nil)
}

// Listed waits until the original's payment listing, which its sweep reads (listTwinsInNamespace),
// holds the payment: substrate's listing lags its writes, and a sweep sent at once misses the payment.
func (s *Session) Listed() {
	filter := url.QueryEscape(fmt.Sprintf(`{"merchant_id":%q}`, s.ids["subBrandId"]))
	payment := s.ids["payment"]
	s.Observe("the sweep can see the payment", "substrate", http.MethodGet, "/twins/cashier-payment?filter="+filter, func(r Response) bool {
		return strings.Contains(string(r.Raw), "cashier-payment/"+payment)
	})
}

// Sweep is the original's expiry sweep with the window set to zero: the deadline passing, driven by
// the route the CronJob calls (recording contract: a time-driven step, replayed as the kit's clock).
func (s *Session) Sweep() Response {
	return s.Merchant("time: the deadline passes (expire-stale sweep)", http.MethodPost,
		"/internal/v1/cashier/payments/expire-stale?olderThanMinutes=0", nil, Internal, nil)
}

// Abandon is the player closing the cashier, as casino-service calls it with the player it authenticated.
func (s *Session) Abandon(player string) Response {
	path := fmt.Sprintf("/internal/v1/cashier/payments/%s/abandon?brandId=%s&playerId=%s", s.ids["payment"], s.ids["brand"], player)
	return s.Merchant("abandon", http.MethodPost, path, nil, Internal, nil)
}

// Undelivered marks the payment's gateway session as holding an outcome it could not deliver, as
// psp-gateway does (psp-checkout-session twin, `outcome_undelivered`).
func (s *Session) Undelivered() {
	txn := s.Read().Str("pspTransactionId")
	if txn == "" {
		s.t.Fatalf("the payment carries no provider reference to mark undelivered")
	}
	s.remember("pspReference", txn)
	s.Want(s.Setup("the gateway holds the outcome undelivered", "substrate", http.MethodPost, "/twins/psp-checkout-session/"+txn,
		map[string]any{"outcome_undelivered": "true"}, NoAuth), 201)
}
