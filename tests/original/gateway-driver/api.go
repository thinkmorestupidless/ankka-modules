package gatewaydriver

import (
	"encoding/json"
	"html"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"sort"
	"strings"
	"time"
)

// The original's gateway, psp-stub and the receiver, one helper per call the scenarios make
// (cmd/psp-gateway/main.go for the gateway's routes). Every id the gateway mints is captured from the
// answer that gave it, so a replay fills it from the rebuild's.

// Use starts a scenario on provider p with the stub's credentials on its terminal.
func (s *Session) Use(p Provider) { s.UseWith(p, p.Properties) }

// UseWith starts a scenario on provider p with these terminal properties: psp-stub's control is set
// to `pending`, so no webhook goes to the gateway before the scenario sends one, and the receiver
// is given the terminal, which the gateway reads back when a webhook arrives.
func (s *Session) UseWith(p Provider, properties map[string]string) {
	s.ID("subBrand")
	s.ID("payment")
	s.baseline(p)
	s.Control(p, map[string]any{"outcome": "pending"})
	s.Terminal(properties)
}

// baseline notes how many requests psp-stub holds before the scenario, unrecorded.
func (s *Session) baseline(p Provider) {
	resp, err := s.cfg.Client.Get(strings.TrimRight(s.cfg.StubHost, "/") + ":" + p.Port + "/__received")
	if err != nil {
		s.t.Fatalf("psp-stub's %s does not answer: %v", p.Name, err)
	}
	defer resp.Body.Close()
	var all []any
	_ = json.NewDecoder(resp.Body).Decode(&all)
	s.seen[p.Name] = len(all)
}

// Control forces psp-stub's sticky outcome for p.
func (s *Session) Control(p Provider, control map[string]any) Response {
	return s.Want(s.Setup("psp-stub control: "+control["outcome"].(string), s.stub(p), http.MethodPost, "/__control", control), 200)
}

// Terminal gives the receiver the scenario's terminal, as merchant-service holds it.
func (s *Session) Terminal(properties map[string]string) Response {
	return s.Want(s.Setup("merchant-service holds the terminal", s.receiver(), http.MethodPut, "/__terminals/"+s.ID("terminal"),
		map[string]any{"properties": properties}), 200)
}

// Script makes the receiver answer the outcome calls for the scenario's payment (or request) so.
func (s *Session) Script(match string, answers ...Answer) Response {
	return s.Want(s.Setup("the receiver answers "+describe(answers), s.receiver(), http.MethodPost, "/__script",
		map[string]any{"match": match, "answers": answers}), 200)
}

func describe(answers []Answer) string {
	var words []string
	for _, a := range answers {
		if a.SilentMs > 0 {
			words = append(words, "nothing")
		} else {
			words = append(words, http.StatusText(a.Status))
		}
	}
	return strings.Join(words, ", then ")
}

// initiation is the body merchant-service's cashier sends (CashierRoutes.callHppInitiate): the
// callback to the receiver's capture route for the payment, the terminal's properties (all an adapter
// reads, CashierRoutes.scala:400-407) and the terminal's id, which the gateway keeps for webhooks.
func (s *Session) initiation(p Provider, payment string, properties map[string]string) map[string]any {
	country := "GB"
	if p.Currency == "CAD" {
		country = "CA"
	}
	return map[string]any{
		"amount": p.Amount, "currency": p.Currency, "method": p.Method,
		"callbackUrl": strings.TrimRight(s.cfg.ReceiverURL, "/") + "/v1/payments/" + s.ID(payment) + "/capture",
		"psp":         p.PSP, "subBrandId": s.ID("subBrand"), "customerId": s.ID("player"),
		"redirectUrl":   "https://merchant.example/return",
		"customerEmail": "player@example.com", "customerFirstName": "Pat", "customerLastName": "Player",
		"customerPhone": "+447700900123", "customerMobile": "4165550100", "customerProvince": "ON",
		"customerIp": "127.0.0.1", "customerCountry": country, "customerCity": "London",
		"customerAddress": "1 Test Street", "customerPostCode": "N1 1AA", "customerState": "London",
		"terminalProperties": properties, "terminalId": s.ID("terminal"),
	}
}

// Initiate asks the gateway for a deposit for `payment` and captures the reference it answers under
// `reference` (and, for a crypto deposit, the quote).
func (s *Session) Initiate(name string, p Provider, payment, reference string, properties map[string]string) Response {
	r := s.Gateway(name, http.MethodPost, "/v1/psp/hpp/initiate", s.initiation(p, payment, properties), nil)
	if r.Status != http.StatusOK || r.Str("pspTransactionId") == "" {
		return r
	}
	s.Grab(reference, "$.pspTransactionId", r.Str("pspTransactionId"))
	if p.Crypto {
		s.Grab("cryptoAmount", "$.cryptoAmount", r.Str("cryptoAmount"))
		// psp-stub's Morphosis address ends in QVEI's id (tests/stubs/morphosis, depositAddress), which a
		// forged callback must carry; the gateway's answer has no other trace of it.
		if id := strings.TrimPrefix(r.Str("paymentAddress"), "bc1qstub"); p.Name == "morphosis" && id != r.Str("paymentAddress") {
			s.facts["wpbId"] = id
		}
	}
	return r
}

// Deposit initiates the scenario's deposit and lets the provider see it: the provider call the
// gateway made, or for DreamzPay the player's browser taking the relay page to the stub.
func (s *Session) Deposit(p Provider) Response {
	return s.DepositAs(p, "payment", "reference")
}

// DepositAs is Deposit for another payment of the same scenario.
func (s *Session) DepositAs(p Provider, payment, reference string) Response {
	r := s.Want(s.Initiate("initiate", p, payment, reference, p.Properties), 200)
	s.Reach(p, reference)
	return r
}

// Reach is the provider seeing the deposit under reference.
func (s *Session) Reach(p Provider, reference string) {
	if p.Name == "dreamzpay" {
		s.Relay(p, reference)
	}
	s.ProviderCalls(p, 1)
}

var hiddenInput = regexp.MustCompile(`<input type="hidden" name="([^"]*)" value="([^"]*)">`)

// Relay is the player's browser on DreamzPay's relay page: the gateway's page, then the form it
// auto-posts to DreamzPay's checkout (here psp-stub's, whatever address the page names).
func (s *Session) Relay(p Provider, reference string) {
	r := s.Want(s.Gateway("the player's browser opens the relay page", http.MethodGet, "/v1/psp/checkout/dreamzpay/"+s.Value(reference), nil, nil), 200)
	form := url.Values{}
	for _, m := range hiddenInput.FindAllStringSubmatch(string(r.Raw), -1) {
		form.Set(html.UnescapeString(m[1]), html.UnescapeString(m[2]))
	}
	if len(form) == 0 {
		s.t.Errorf("%s: the relay page carries no form: %s", s.rec.Scenario, string(r.Raw))
		return
	}
	s.Setup("the player's browser posts the relay form to DreamzPay", s.stub(p), http.MethodPost, "/transaction/Checkout", Form(form))
}

// ProviderCalls observes the requests the gateway made to p since the scenario last looked, waiting
// for at least `want` (none: after cfg.Settle). Form bodies are recorded as their fields, and the
// values a forged webhook needs (the Cozeni token, the Morphosis notify token) are noted.
func (s *Session) ProviderCalls(p Provider, want int) []any {
	if want == 0 {
		time.Sleep(s.cfg.Settle)
	}
	from := s.seen[p.Name]
	kept := s.Observe("the provider call", s.stub(p), "/__received", func(raw []byte) any {
		var all []map[string]any
		_ = json.Unmarshal(raw, &all)
		if from > len(all) {
			from = 0 // psp-stub restarted
		}
		out := []any{}
		for _, e := range all[from:] {
			out = append(out, s.note(p, e))
		}
		return out
	}, func(v any) bool { return len(v.([]any)) >= want })
	s.seen[p.Name] = from + len(kept.([]any))
	return kept.([]any)
}

// note reads one received request for the facts forging needs, and gives its form body as fields.
func (s *Session) note(p Provider, e map[string]any) map[string]any {
	if h, ok := e["headers"].(map[string]any); ok {
		if v, ok := h["X-Cozeni-Txnauthz"].(string); ok && v != "" {
			s.facts["txnauthz"] = v
		}
	}
	if text, ok := e["body"].(string); ok {
		if form, ok := asForm(text); ok {
			fields := map[string]any{}
			for k := range form {
				fields[k] = form.Get(k)
			}
			e["body"] = fields
			if n := form.Get("notification_url"); n != "" {
				if u, err := url.Parse(n); err == nil {
					switch {
					case strings.Contains(u.Path, "/withdrawal/"):
						s.facts["payoutToken"] = path.Base(u.Path)
					case strings.Contains(u.Path, "/deposit/"):
						s.facts["notifyToken"] = path.Base(u.Path)
					}
				}
			}
		}
	}
	return e
}

// Webhook has psp-stub send p's webhook for reference now, with the control's fields over its sticky
// control; the step's answer is the gateway's (`{"status","body"}`).
func (s *Session) Webhook(name string, p Provider, reference string, control map[string]any) Response {
	body := map[string]any{"reference": reference}
	for k, v := range control {
		body[k] = v
	}
	return s.call("webhook: "+name, s.stub(p), http.MethodPost, "/__webhook", body, nil, nil)
}

// Forged posts a webhook the driver wrote to the gateway itself. Its signature is over the stub's
// key (or another), so a replay re-signs it rather than resend these bytes.
func (s *Session) Forged(name string, p Provider, f Forgery) Response {
	h := p.forge(s, p, f)
	return s.Gateway("webhook (forged): "+name, http.MethodPost, h.Path, h.Body, h.Headers)
}

// Outcomes observes the outcome calls the gateway made for the scenario's payments and requests,
// after cfg.Settle, waiting for at least `want`.
func (s *Session) Outcomes(name string, want int) []any {
	time.Sleep(s.cfg.Settle)
	q := url.Values{}
	names := append([]string(nil), s.order...)
	sort.Strings(names)
	for _, n := range names {
		if strings.HasPrefix(n, "payment") || strings.HasPrefix(n, "request") {
			q.Add("path", s.ids[n])
		}
	}
	if len(q) == 0 {
		q.Add("path", s.ID("payment")) // never every call: a scenario with no payment yet expects none
	}
	return s.Observe(name, s.receiver(), "/__received?"+q.Encode(), func(raw []byte) any {
		var calls []any
		_ = json.Unmarshal(raw, &calls)
		if calls == nil {
			calls = []any{}
		}
		return calls
	}, func(v any) bool { return len(v.([]any)) >= want }).([]any)
}

// Return is the player's browser coming back from p to the gateway.
func (s *Session) Return(name string, p Provider, reference, method string, body any) Response {
	return s.Gateway(name, method, strings.Replace(p.Return, "{reference}", reference, 1), body, nil)
}

// PayoutInitiate asks the gateway to pay out the withdrawal request minted under `request`.
func (s *Session) PayoutInitiate(name string, p Provider, request string) Response {
	return s.Gateway(name, http.MethodPost, p.Payout.Route, p.Payout.Body(s, request), nil)
}

// PayoutStatus is the original's Unity Finance status read for the request (main.go:174), which
// casino-service's sweeper calls with the sub-brand and terminal.
func (s *Session) PayoutStatus(name, request string) Response {
	q := url.Values{"merchantId": {s.ID("subBrand")}, "terminalId": {s.ID("terminal")}}
	return s.Gateway(name, http.MethodGet, "/v1/psp/payout/unityfinance/"+s.ID(request)+"/status?"+q.Encode(), nil, nil)
}

// Redrive is the original's re-drive CronJob's call (main.go:178-181), under the internal secret.
func (s *Session) Redrive(name string) Response {
	return s.Time(name+" (redrive-undelivered)", http.MethodPost, "/internal/v1/psp/redrive-undelivered",
		map[string]string{"X-Internal-Secret": s.cfg.InternalSecret})
}

// WantPresent records each named field the answer lacks as a failure of the scenario.
func (s *Session) WantPresent(r Response, fields ...string) Response {
	for _, f := range fields {
		if r.Str(f) == "" {
			s.t.Errorf("%s: the answer carries no %s: %s", s.rec.Scenario, f, string(r.Raw))
		}
	}
	return r
}
