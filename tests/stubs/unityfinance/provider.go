// Package unityfinance answers as Unity Finance does to the original's psp-gateway adapter
// (eitheror-platform/services/psp-gateway/internal/unityfinance/adapter.go), in the shapes
// confirmed live, and sends its signed webhooks. psp-stub serves it on 18108. It replaces the
// original's tests/uf-stub, which speaks none of the live payout shapes.
package unityfinance

// The routes, as the adapter calls them:
//
//   POST {UNITYFINANCE_PAY_URL}                  the invoice: an ik_* form signed with ik_sign, with
//                                                ik_int=json, answered with resultData.paymentForm
//                                                (adapter.go:45-226)
//   POST {UNITYFINANCE_API_URL}/withdraw         a payout: a form with Basic userId:withdrawalApiKey
//                                                and Ik-Api-Account-Id; {"status":"ok","data":{"withdraw":{"id"}}}
//                                                or {"status":"error","message"} (adapter.go:327-447)
//   GET  {UNITYFINANCE_API_URL}/withdraw?paymentNo=  the status read: {"status":"ok","data":[…]} with
//                                                the state as a quoted number (adapter.go:520-575)
//
// Webhooks, both form-encoded and signed with ik_sign = base64(SHA-256(sorted values joined ":" +
// ":" + signKey)) over every posted field but ik_sign (signature.go:11-35):
//
//   the deposit's, to the invoice's ik_ia_u (<gateway>/v1/webhooks/unityfinance): ik_co_id, ik_pm_no,
//   ik_inv_st (success, canceled, expired, process, chargebacked), … (handler/webhook.go:264-418)
//
//   the payout's, to its interactionUrl (<gateway>/v1/webhooks/unityfinance/withdrawal?merchantId=…):
//   id, paymentNo and state by name (success, canceled, limitExceeded, chargebacked), confirmed live
//   2026-08-06 (handler/withdrawal_webhook.go:19-38).

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

// The default signKey is the one the original's own secrets test gives a Unity Finance terminal
// (internal/handler/session_secrets_test.go:116). It must equal the terminal's signKey.
const defaultSignKey = "unityfinance-signing-secret"

// Config is what the stub needs beyond the requests.
type Config struct {
	// SignKey verifies the invoice's ik_sign and signs both webhooks.
	SignKey string
	// UserId and APIKey, when set, are the Basic credentials a payout or status read must carry;
	// empty, any non-empty pair is accepted.
	UserId, APIKey string
	// PublicURL is how a browser reaches this stub, for the payment form's action.
	PublicURL string
	// WebhookURL, when set, replaces ik_ia_u and interactionUrl up to /v1/webhooks/unityfinance
	// (the payout's /withdrawal and query are kept).
	WebhookURL string
	// Delay is how long after the invoice or payout its webhook is sent.
	Delay time.Duration
}

// ConfigFromEnv reads UNITYFINANCE_STUB_* variables.
func ConfigFromEnv(getenv func(string) string) Config {
	c := Config{
		SignKey:    defaultSignKey,
		UserId:     getenv("UNITYFINANCE_STUB_USER_ID"),
		APIKey:     getenv("UNITYFINANCE_STUB_WITHDRAWAL_API_KEY"),
		PublicURL:  "http://localhost:18108",
		WebhookURL: getenv("UNITYFINANCE_STUB_WEBHOOK_URL"),
		Delay:      500 * time.Millisecond,
	}
	if v := getenv("UNITYFINANCE_STUB_SIGN_KEY"); v != "" {
		c.SignKey = v
	}
	if v := getenv("UNITYFINANCE_STUB_PUBLIC_URL"); v != "" {
		c.PublicURL = v
	}
	if v := getenv("UNITYFINANCE_STUB_WEBHOOK_DELAY_MS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			c.Delay = time.Duration(n) * time.Millisecond
		}
	}
	return c
}

// Start is the stub as psp-stub runs it.
func Start(getenv func(string) string) (*stubhttp.Stub, http.Handler) {
	stub := stubhttp.New("unityfinance")
	return stub, NewProvider(stub, ConfigFromEnv(getenv)).Routes()
}

// Sign is Unity Finance's ik_sign (signature.go:11-35): the values sorted by key, joined with ":",
// then ":" + signKey, SHA-256, standard base64.
func Sign(params map[string]string, signKey string) string {
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	vals := make([]string, 0, len(keys))
	for _, k := range keys {
		vals = append(vals, params[k])
	}
	sum := sha256.Sum256([]byte(strings.Join(vals, ":") + ":" + signKey))
	return base64.StdEncoding.EncodeToString(sum[:])
}

// signed is the form with ik_sign over every other field.
func signed(form url.Values, signKey string) url.Values {
	params := map[string]string{}
	for k, v := range form {
		if k != "ik_sign" && len(v) > 0 {
			params[k] = v[0]
		}
	}
	form.Set("ik_sign", Sign(params, signKey))
	return form
}

type invoice struct {
	id, coId, ref, amount, currency, method, target, success, fail string
}

type withdrawal struct {
	id, paymentNo, purseId, amount, first, last, iban, target string
	state                                                     string // the status read's numeric state
	recorded                                                  bool
}

// Provider answers Unity Finance's routes.
type Provider struct {
	stub   *stubhttp.Stub
	cfg    Config
	client *http.Client

	mu          sync.Mutex
	invoices    map[string]*invoice    // by ik_pm_no, the gateway's txnRef, and by invoice id
	withdrawals map[string]*withdrawal // by paymentNo, the payout's request id
}

func NewProvider(stub *stubhttp.Stub, cfg Config) *Provider {
	p := &Provider{stub: stub, cfg: cfg, client: &http.Client{Timeout: 10 * time.Second},
		invoices: map[string]*invoice{}, withdrawals: map[string]*withdrawal{}}
	stub.Supports(stubhttp.Duplicate, stubhttp.NoRecord, stubhttp.Reversed, stubhttp.Unauthorised)
	stub.OnWebhook(p.send)
	return p
}

// Routes serves the API under /v1 (UNITYFINANCE_API_URL is the host plus /v1, as the original's
// default https://old-api.unityfinance.com/v1) and the invoice on any other POST path
// (UNITYFINANCE_PAY_URL is a bare host).
func (p *Provider) Routes() http.Handler {
	mux := http.NewServeMux()
	for _, prefix := range []string{"/v1", ""} {
		mux.HandleFunc("POST "+prefix+"/withdraw", p.withdraw)
		mux.HandleFunc("GET "+prefix+"/withdraw", p.status)
	}
	mux.HandleFunc("GET /checkout/{id}", p.page)
	mux.HandleFunc("POST /", p.invoice)
	return mux
}

func ppiError(w http.ResponseWriter, code int, msg string) {
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{"resultCode": code, "resultMsg": msg})
}

// invoice answers the HPP's JSON interface (ik_int=json): a payment form for the player.
func (p *Provider) invoice(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		ppiError(w, 1, "invalid form")
		return
	}
	f := r.PostForm
	for _, k := range []string{"ik_co_id", "ik_pm_no", "ik_am", "ik_cur", "ik_sign"} {
		if f.Get(k) == "" {
			ppiError(w, 2, "missing "+k)
			return
		}
	}
	params := map[string]string{}
	for k, v := range f {
		if k != "ik_sign" && len(v) > 0 {
			params[k] = v[0]
		}
	}
	if Sign(params, p.cfg.SignKey) != f.Get("ik_sign") {
		// UF answers a signature mismatch with a bare "error 115" (adapter.go:150-153).
		log.Printf("unityfinance stub: ik_sign mismatch for %s — is UNITYFINANCE_STUB_SIGN_KEY the terminal's signKey?", f.Get("ik_pm_no"))
		ppiError(w, 115, "error 115")
		return
	}
	inv := &invoice{
		id: newId("3"), coId: f.Get("ik_co_id"), ref: f.Get("ik_pm_no"), amount: f.Get("ik_am"), currency: f.Get("ik_cur"),
		method: f.Get("ik_payment_method"), target: stubhttp.Retarget(f.Get("ik_ia_u"), p.cfg.WebhookURL, "/v1/webhooks/unityfinance"),
		success: f.Get("ik_suc_u"), fail: f.Get("ik_fal_u"),
	}
	p.mu.Lock()
	p.invoices[inv.ref] = inv
	p.invoices[inv.id] = inv
	p.mu.Unlock()

	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"resultCode": 0,
		"resultMsg":  "Success",
		"resultData": map[string]any{
			"paymentForm": map[string]any{
				"action":     strings.TrimRight(p.cfg.PublicURL, "/") + "/checkout/" + inv.id,
				"method":     "get",
				"parameters": map[string]string{"ik_pm_no": inv.ref},
			},
		},
	})
	p.later(inv.ref, p.stub.Control())
}

// page stands in for the hosted payment page: it sends the player to ik_suc_u, or ik_fal_u when
// the forced outcome is a failure.
func (p *Provider) page(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	inv, ok := p.invoices[r.PathValue("id")]
	p.mu.Unlock()
	if !ok {
		http.NotFound(w, r)
		return
	}
	back := inv.success
	if p.stub.Next() == stubhttp.Failure {
		back = inv.fail
	}
	http.Redirect(w, r, back, http.StatusFound)
}

func apiError(w http.ResponseWriter, status, code int, msg string) {
	stubhttp.WriteJSON(w, status, map[string]any{"status": "error", "code": code, "data": map[string]int{"innerCode": 0}, "message": msg})
}

// auth answers as UF does to absent or wrong credentials: 401 before anything else
// (adapter.go:409-424).
func (p *Provider) auth(w http.ResponseWriter, r *http.Request) bool {
	user, key, ok := r.BasicAuth()
	switch {
	case !ok || user == "" || key == "" || p.stub.Next() == stubhttp.Unauthorised,
		p.cfg.UserId != "" && user != p.cfg.UserId,
		p.cfg.APIKey != "" && key != p.cfg.APIKey:
		apiError(w, http.StatusUnauthorized, 4, "Auth: Invalid login or password provided")
		return false
	case r.Header.Get("Ik-Api-Account-Id") == "":
		apiError(w, http.StatusUnauthorized, 4, "Auth: Ik-Api-Account-Id header is required")
		return false
	}
	return true
}

func (p *Provider) withdraw(w http.ResponseWriter, r *http.Request) {
	if !p.auth(w, r) {
		return
	}
	if err := r.ParseForm(); err != nil {
		apiError(w, http.StatusOK, 2, "invalid form")
		return
	}
	f := r.PostForm
	no := f.Get("paymentNo")
	if no == "" || f.Get("amount") == "" {
		apiError(w, http.StatusOK, 2, "paymentNo and amount are required")
		return
	}
	control := p.stub.Control()
	p.mu.Lock()
	_, seen := p.withdrawals[no]
	p.mu.Unlock()
	if seen || control.Outcome == stubhttp.Duplicate {
		// The adapter reads "duplicate" or "already exist" as already initiated (adapter.go:430-436).
		apiError(w, http.StatusOK, 11, fmt.Sprintf("Withdraw with paymentNo %s already exists (duplicate)", no))
		return
	}
	target := f.Get("interactionUrl")
	if target == "" && p.cfg.WebhookURL != "" {
		target = strings.TrimRight(p.cfg.WebhookURL, "/") + "/withdrawal"
	} else {
		target = stubhttp.Retarget(target, p.cfg.WebhookURL, "/v1/webhooks/unityfinance")
	}
	wd := &withdrawal{
		id: newId("49"), paymentNo: no, purseId: f.Get("purseId"), amount: f.Get("amount"),
		first: f.Get("details[first_name]"), last: f.Get("details[last_name]"), iban: f.Get("details[iban]"),
		target: target, state: "1", recorded: control.Outcome != stubhttp.NoRecord,
	}
	p.mu.Lock()
	p.withdrawals[no] = wd
	p.mu.Unlock()

	// The live acceptance (adapter.go:349-357): data.withdraw with an id is the proof.
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"code":    0,
		"message": "Success",
		"data": map[string]any{
			"withdraw":       map[string]any{"id": wd.id, "paymentNo": no, "state": 1, "result": 0, "psResultDesc": "Success"},
			"transaction":    map[string]any{},
			"@resultCode":    0,
			"@resultMessage": "Success",
		},
	})
	if control.Outcome == stubhttp.NoRecord {
		return // UF lost it: no webhook, and the status read holds nothing
	}
	p.later(no, control)
}

var stateNames = map[string]string{"1": "process", "8": "success", "9": "canceled", "20": "limitExceeded", "11": "chargebacked"}

// status is the payout status read, the array shape confirmed live (adapter.go:493-511).
func (p *Provider) status(w http.ResponseWriter, r *http.Request) {
	if !p.auth(w, r) {
		return
	}
	no := r.URL.Query().Get("paymentNo")
	p.mu.Lock()
	wd, ok := p.withdrawals[no]
	var row map[string]string
	if ok && wd.recorded {
		row = map[string]string{"id": wd.id, "paymentNo": no, "state": wd.state, "result": "0", "psResultDesc": "Success", "stateName": stateNames[wd.state]}
	}
	p.mu.Unlock()
	data := []map[string]string{}
	if row != nil && p.stub.Next() != stubhttp.NoRecord {
		data = append(data, row)
	}
	stubhttp.WriteJSON(w, http.StatusOK, map[string]any{"status": "ok", "code": 0, "data": data})
}

func (p *Provider) later(ref string, c stubhttp.Control) {
	if c.Outcome == stubhttp.Pending {
		p.stub.Hold(ref) // no webhook until the next control settles it
		return
	}
	go func() {
		time.Sleep(c.Delay(p.cfg.Delay))
		_, _ = p.send(ref, c)
	}()
}

// depositState is ik_inv_st for a control: success; failure canceled, or expired when the code
// says so (any other code is ik_inv_cancel_code); pending process; reversed chargebacked.
func depositState(c stubhttp.Control) (string, error) {
	switch c.Outcome {
	case stubhttp.Success:
		return "success", nil
	case stubhttp.Failure:
		if c.Code == "expired" {
			return "expired", nil
		}
		return "canceled", nil
	case stubhttp.Pending:
		return "process", nil
	case stubhttp.Reversed:
		return "chargebacked", nil
	default:
		return "", fmt.Errorf("unityfinance has no deposit webhook for %q", c.Outcome)
	}
}

// withdrawalState is the payout webhook's state name and the status read's code for a control:
// success 8; failure canceled 9, or limitExceeded 20 when the code is "limitExceeded" or "20";
// pending process 1; reversed chargebacked 11.
func withdrawalState(c stubhttp.Control) (name, code string, err error) {
	switch c.Outcome {
	case stubhttp.Success:
		return "success", "8", nil
	case stubhttp.Failure:
		if c.Code == "limitExceeded" || c.Code == "20" {
			return "limitExceeded", "20", nil
		}
		return "canceled", "9", nil
	case stubhttp.Pending:
		return "process", "1", nil
	case stubhttp.Reversed:
		return "chargebacked", "11", nil
	default:
		return "", "", fmt.Errorf("unityfinance has no withdrawal webhook for %q", c.Outcome)
	}
}

// send delivers the deposit's or the payout's webhook for a reference now.
func (p *Provider) send(ref string, c stubhttp.Control) (stubhttp.Delivery, error) {
	p.mu.Lock()
	inv, isInvoice := p.invoices[ref]
	wd, isWithdrawal := p.withdrawals[ref]
	p.mu.Unlock()
	var form url.Values
	var target string
	switch {
	case isInvoice:
		st, err := depositState(c)
		if err != nil {
			return stubhttp.Delivery{}, err
		}
		form = url.Values{
			"ik_co_id": {inv.coId}, "ik_pm_no": {inv.ref}, "ik_inv_id": {inv.id}, "ik_inv_st": {st},
			"ik_inv_crt": {time.Now().UTC().Format("2006-01-02 15:04:05")}, "ik_am": {inv.amount}, "ik_cur": {inv.currency},
		}
		if inv.method != "" {
			form.Set("ik_pw_via", inv.method)
		}
		if st == "canceled" && c.Code != "" {
			form.Set("ik_inv_cancel_code", c.Code)
		}
		target = inv.target
	case isWithdrawal:
		name, code, err := withdrawalState(c)
		if err != nil {
			return stubhttp.Delivery{}, err
		}
		p.mu.Lock()
		wd.state = code
		p.mu.Unlock()
		form = url.Values{
			"id": {wd.id}, "purseId": {wd.purseId}, "paymentNo": {wd.paymentNo}, "state": {name}, "processed": {""},
			"payerWriteoffAmount": {wd.amount}, "payeeReceiveAmount": {wd.amount}, "created": {time.Now().UTC().Format("2006-01-02 15:04:05")},
			"details_first_name": {wd.first}, "details_last_name": {wd.last}, "details_iban": {wd.iban},
		}
		target = wd.target
	default:
		return stubhttp.Delivery{}, stubhttp.ErrUnknownReference
	}
	if target == "" {
		return stubhttp.Delivery{}, fmt.Errorf("no webhook address for %s: the request named none and UNITYFINANCE_STUB_WEBHOOK_URL is unset", ref)
	}
	body := signed(form, p.cfg.SignKey).Encode()
	d := stubhttp.Post(p.client, target, "application/x-www-form-urlencoded", nil, []byte(body))
	log.Printf("unityfinance stub: webhook for %s to %s answered %d (err=%v)", ref, target, d.Status, d.Err)
	return d, nil
}

func newId(prefix string) string {
	n, _ := rand.Int(rand.Reader, big.NewInt(900_000))
	return prefix + strconv.FormatInt(n.Int64()+100_000, 10)
}
