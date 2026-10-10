package main

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
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
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

// Provider answers QVEI's routes and sends its callbacks.
type Provider struct {
	stub   *stubhttp.Stub
	delay  time.Duration
	client *http.Client
	nextID atomic.Int64
}

// CallbackDelayFromEnv reads MORPHOSIS_STUB_CALLBACK_DELAY_MS (default 1000).
func CallbackDelayFromEnv() time.Duration {
	if v := os.Getenv("MORPHOSIS_STUB_CALLBACK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			return time.Duration(n) * time.Millisecond
		}
	}
	return time.Second
}

// NewProvider builds the QVEI handler. QVEI ids are sequential integers; the stub starts at 5473000.
func NewProvider(stub *stubhttp.Stub, delay time.Duration) *Provider {
	p := &Provider{stub: stub, delay: delay, client: &http.Client{Timeout: 10 * time.Second}}
	p.nextID.Store(5473000)
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
		p.insertDeposit(w, r.PostForm)
	case "insert_payout":
		p.insertPayout(w, r.PostForm)
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

// underpay is 90% of a quoted coin amount: the received amount of an underpaid deposit.
func underpay(quoted string) string {
	f, _ := strconv.ParseFloat(quoted, 64)
	return strconv.FormatFloat(f*0.9, 'f', 8, 64)
}

func (p *Provider) insertDeposit(w http.ResponseWriter, form url.Values) {
	if f := missing(form, "user", "pass", "amount", "currency", "method", "notification_url"); f != "" {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody("missing "+f))
		return
	}
	coin, err := CoinAmount(form.Get("amount"), form.Get("method"))
	if err != nil {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody(err.Error()))
		return
	}
	id := strconv.FormatInt(p.nextID.Add(1), 10)
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"result":      "ok",
		"status":      "pe",
		"id":          json.Number(id),
		"address":     depositAddress(form.Get("method"), id),
		"coin_amount": json.Number(coin),
	})

	cb := Callback{Id: json.Number(id), TxHash: "stub-tx-" + id}
	switch p.stub.Next() {
	case stubhttp.Success:
		cb.Status, cb.CoinAmount = "ac", json.Number(coin)
	case stubhttp.Failure:
		// Confirmed on chain, but short of the quote: the gateway fails it as underpaid
		// (handler/morphosis.go, morphosis.Underpaid).
		cb.Status, cb.CoinAmount = "ac", json.Number(underpay(coin))
	case stubhttp.Pending:
		cb.Status, cb.CoinAmount = "pe", json.Number("0")
	}
	p.deliverLater(form.Get("notification_url"), cb)
}

func (p *Provider) insertPayout(w http.ResponseWriter, form url.Values) {
	if f := missing(form, "user", "pass", "amount", "method", "address", "notification_url"); f != "" {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody("missing "+f))
		return
	}
	coin, err := CoinAmount(form.Get("amount"), form.Get("method"))
	if err != nil {
		stubhttp.WriteJSON(w, http.StatusOK, errorBody(err.Error()))
		return
	}
	id := strconv.FormatInt(p.nextID.Add(1), 10)
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"result":      "ok",
		"status":      "pe",
		"id":          json.Number(id),
		"coin_amount": json.Number(coin),
		"processed":   "0",
	})

	cb := Callback{Id: json.Number(id), CoinAmount: json.Number(coin)}
	switch p.stub.Next() {
	case stubhttp.Success:
		cb.Status, cb.TxHash = "ac", "stub-tx-"+id
	case stubhttp.Failure:
		cb.Status, cb.Message = "de", "payout denied by stub"
	case stubhttp.Pending:
		cb.Status = "pe"
	}
	p.deliverLater(form.Get("notification_url"), cb)
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
func (p *Provider) deliverLater(target string, cb Callback) {
	body, _ := json.Marshal(cb)
	go func() {
		time.Sleep(p.delay)
		for attempt := 1; attempt <= 5; attempt++ {
			req, err := http.NewRequest(http.MethodPost, target, bytes.NewReader(body))
			if err != nil {
				log.Printf("morphosis stub: bad notification_url %q: %v", target, err)
				return
			}
			req.Header.Set("Content-Type", "application/json")
			resp, err := p.client.Do(req)
			if err == nil {
				answer, _ := io.ReadAll(resp.Body)
				resp.Body.Close()
				if resp.StatusCode == http.StatusOK && strings.TrimSpace(string(answer)) == "OK" {
					log.Printf("morphosis stub: callback id=%s status=%s delivered to %s", cb.Id, cb.Status, target)
					return
				}
				err = fmt.Errorf("HTTP %d %q", resp.StatusCode, string(answer))
			}
			log.Printf("morphosis stub: callback id=%s attempt %d to %s failed: %v", cb.Id, attempt, target, err)
			time.Sleep(time.Second)
		}
	}()
}
