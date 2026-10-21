// Package morphosis answers as QVEI's WPB API (Morphosis) does to the original's psp-gateway
// adapter, and posts its callbacks. psp-stub serves it on 18106.
package morphosis

// The QVEI WPB API as the original's adapter uses it
// (eitheror-platform/services/psp-gateway/internal/morphosis/adapter.go):
//
//   POST {MORPHOSIS_BASE_URL}?action=insert_deposit   form: user, pass, account, amount, currency,
//                                                      method, trace_id, notification_url
//   POST {MORPHOSIS_BASE_URL}?action=insert_payout    form: user, pass, account, amount, method,
//                                                      address, notification_url[, details]
//
// Both answer JSON with `id` and `coin_amount` as JSON numbers (adapter.go depositResponse and
// payoutResponse). The outcome arrives later as a POST of JSON to the notification_url the adapter
// sent: {"id":N,"status":"ac|de|pe","tx_hash":…,"coin_amount":N,"message":…}, and QVEI expects the
// plain-text body "OK" back (handler/morphosis.go morphosisOK). QVEI does not sign callbacks; the
// gateway authenticates them by the random notify token in the notification_url path and by the id
// matching the one insert_deposit returned (morphosis/sessions.go).

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// rates is the stub's USD price of one coin, keyed by the QVEI method name the adapter sends
// (adapter.go mapMethodName). Any method not listed is priced at 1 (a stablecoin).
var rates = map[string]float64{
	"bitcoin":     60000,
	"ethereum":    3000,
	"litecoin":    80,
	"ripple":      0.5,
	"dogecoin":    0.15,
	"bitcoincash": 400,
	"binance":     550,
	"avalanche":   30,
	"solana":      150,
	"cardano":     0.45,
	"tron":        0.12,
	"stellar":     0.1,
	"dash":        30,
	"matic":       0.7,
	"shibainu":    0.00002,
	"chainlink":   15,
	"apecoin":     1.2,
	"0x":          0.4,
}

// Callback is one notification QVEI posts to a notification_url.
type Callback struct {
	Id         json.Number `json:"id"`
	Status     string      `json:"status"`
	TxHash     string      `json:"tx_hash"`
	CoinAmount json.Number `json:"coin_amount"`
	Message    string      `json:"message,omitempty"`
}

// Config is the stub's settings.
type Config struct {
	// Delay is how long after the insert the callback is sent.
	Delay time.Duration
	// WebhookURL, when set, replaces the notification_url's address up to /v1/webhooks/morphosis
	// (its /deposit/{token} or /withdrawal/{token} is kept).
	WebhookURL string
}

// ConfigFromEnv reads MORPHOSIS_STUB_CALLBACK_DELAY_MS (default 1000) and MORPHOSIS_STUB_WEBHOOK_URL.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{Delay: time.Second, WebhookURL: getenv("MORPHOSIS_STUB_WEBHOOK_URL")}
	if v := getenv("MORPHOSIS_STUB_CALLBACK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("morphosis")
	return stub, NewProvider(stub, ConfigFromEnv(getenv))
}

// order is one deposit or payout, for its callbacks.
type order struct {
	id     string
	payout bool
	coin   string // the quoted coin amount
	target string
}

// Provider answers QVEI's routes and sends its callbacks.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client
	nextID atomic.Int64

	mu     sync.Mutex
	orders map[string]*order // by QVEI id, by trace_id and by notify token
}

// NewProvider builds the QVEI handler. QVEI ids are sequential integers; the stub starts at 5473000.
func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second}, orders: map[string]*order{}}
	p.nextID.Store(5473000)
	stub.Supports(stubhttp.Underpayment, stubhttp.Overpayment)
	stub.OnWebhook(p.send)
	return p
}

// ServeHTTP answers POST / (with or without a trailing path) by its `action` query parameter, as
// the adapter calls resolveBaseUrl(cfg) + "?action=…".
func (p *Provider) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		stubhttp.WriteJSON(w, http.StatusMethodNotAllowed, errorBody("method not allowed"))
		return
	}
	if err := r.ParseForm(); err != nil {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody("invalid form"))
		return
	}
	switch r.URL.Query().Get("action") {
	case "insert_deposit":
		p.insert(w, r.PostForm, false)
	case "insert_payout":
		p.insert(w, r.PostForm, true)
	default:
		stubhttp.WriteJSON(w, http.StatusOK, errorBody("unknown action"))
	}
}

func errorBody(detail string) map[string]string {
	return map[string]string{"result": "error", "detail": detail}
}

func missing(form url.Values, fields ...string) string {
	for _, f := range fields {
		if form.Get(f) == "" {
			return f
		}
	}
	return ""
}

// CoinAmount converts a fiat amount ("50.00") into the coin amount the stub quotes for method.
func CoinAmount(amount, method string) (string, error) {
	fiat, err := strconv.ParseFloat(amount, 64)
	if err != nil || fiat <= 0 {
		return "", fmt.Errorf("invalid amount %q", amount)
	}
	rate, ok := rates[method]
	if !ok {
		rate = 1
	}
	return strconv.FormatFloat(fiat/rate, 'f', 8, 64), nil
}

// scale is a quoted coin amount times f, to 8 places: the received amount of an under- or
// overpaid deposit.
func scale(quoted string, f float64) string {
	q, _ := strconv.ParseFloat(quoted, 64)
	return strconv.FormatFloat(q*f, 'f', 8, 64)
}

func (p *Provider) insert(w http.ResponseWriter, form url.Values, payout bool) {
	required := []string{"user", "pass", "amount", "currency", "method", "notification_url"}
	if payout {
		required = []string{"user", "pass", "amount", "method", "address", "notification_url"}
	}
	if f := missing(form, required...); f != "" {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody("missing "+f))
		return
	}
	coin, err := CoinAmount(form.Get("amount"), form.Get("method"))
	if err != nil {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody(err.Error()))
		return
	}
	id := strconv.FormatInt(p.nextID.Add(1), 10)
	notify := form.Get("notification_url")
	o := &order{id: id, payout: payout, coin: coin, target: stubhttp.Retarget(notify, p.cfg.WebhookURL, "/v1/webhooks/morphosis")}
	p.mu.Lock()
	p.orders[id] = o
	if u, err := url.Parse(notify); err == nil {
		p.orders[path.Base(u.Path)] = o // the notify token
	}
	if trace := form.Get("trace_id"); trace != "" {
		p.orders[trace] = o
	}
	p.mu.Unlock()

	if payout {
		stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
			"result": "ok", "status": "pe", "id": json.Number(id), "coin_amount": json.Number(coin), "processed": "0",
		})
	} else {
		stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
			"result": "ok", "status": "pe", "id": json.Number(id), "address": depositAddress(form.Get("method"), id), "coin_amount": json.Number(coin),
		})
	}

	control := p.stub.Control()
	if control.Outcome == stubhttp.Pending {
		p.stub.Hold(id)
	}
	cb, err := o.callback(control)
	if err != nil {
		log.Printf("morphosis stub: %v", err)
		return
	}
	p.deliverLater(o.target, cb, control.Delay(p.cfg.Delay))
}

// callback is the notification for one order under a control. Deposits: success ac with the quote;
// failure de with the code as the message; pending pe; underpayment ac with receivedAmount or 90%
// of the quote (the gateway fails it as underpaid, handler/morphosis.go, morphosis.Underpaid);
// overpayment ac with receivedAmount or 110%. Payouts: success ac with a tx_hash, failure de,
// pending pe.
func (o *order) callback(c stubhttp.Control) (Callback, error) {
	cb := Callback{Id: json.Number(o.id), CoinAmount: json.Number(o.coin)}
	if !o.payout {
		cb.TxHash = "stub-tx-" + o.id
	}
	received := func(f float64) json.Number {
		if c.ReceivedAmount != "" {
			return c.ReceivedAmount
		}
		return json.Number(scale(o.coin, f))
	}
	switch c.Outcome {
	case stubhttp.Success:
		cb.Status, cb.TxHash = "ac", "stub-tx-"+o.id
	case stubhttp.Failure:
		cb.Status, cb.Message, cb.TxHash = "de", c.Code, ""
		if cb.Message == "" {
			cb.Message = "denied by stub"
		}
	case stubhttp.Pending:
		cb.Status = "pe"
		if !o.payout {
			cb.CoinAmount = "0"
		}
	case stubhttp.Underpayment, stubhttp.Overpayment:
		if o.payout {
			return Callback{}, fmt.Errorf("a morphosis payout has no %s", c.Outcome)
		}
		f := 0.9
		if c.Outcome == stubhttp.Overpayment {
			f = 1.1
		}
		cb.Status, cb.CoinAmount = "ac", received(f)
	default:
		return Callback{}, fmt.Errorf("morphosis has no callback for %q", c.Outcome)
	}
	return cb, nil
}

// send posts one order's callback now, once, and answers what the gateway said.
func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	o, ok := p.orders[ref]
	p.mu.Unlock()
	if !ok {
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	cb, err := o.callback(c)
	if err != nil {
		return stubhttp.Delivery{}, err
	}
	body, _ := json.Marshal(cb)
	d := stubhttp.Post(p.client, o.target, "application/json", nil, body)
	log.Printf("morphosis stub: callback id=%s status=%s to %s answered %d (err=%v)", cb.Id, cb.Status, o.target, d.Status, d.Err)
	return d, nil
}

// depositAddress is a deterministic, well-formed-looking address for the method.
func depositAddress(method, id string) string {
	switch {
	case method == "bitcoin":
		return "bc1qstub" + id
	case strings.HasSuffix(method, "/trx") || method == "tron":
		return "TStub" + id
	case strings.HasSuffix(method, "/sol") || method == "solana":
		return "StubSol" + id
	case method == "ripple":
		return "rStub" + id
	case method == "litecoin":
		return "ltc1qstub" + id
	default:
		return fmt.Sprintf("0x%040s", id)
	}
}

// deliverLater posts the callback after the delay, retrying (as QVEI does, though far faster)
// until the gateway answers 200 "OK", at most 5 times.
func (p *Provider) deliverLater(target string, cb Callback, delay time.Duration) {
	body, _ := json.Marshal(cb)
	go func() {
		time.Sleep(delay)
		for attempt := 1; attempt <= 5; attempt++ {
			d := stubhttp.Post(p.client, target, "application/json", nil, body)
			err := d.Err
			if err == nil {
				if d.Status == http.StatusOK && strings.TrimSpace(string(d.Body)) == "OK" {
					log.Printf("morphosis stub: callback id=%s status=%s delivered to %s", cb.Id, cb.Status, target)
					return
				}
				err = fmt.Errorf("HTTP %d %q", d.Status, string(d.Body))
			}
			log.Printf("morphosis stub: callback id=%s attempt %d to %s failed: %v", cb.Id, attempt, target, err)
			time.Sleep(time.Second)
		}
	}()
}
