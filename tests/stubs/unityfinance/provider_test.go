package unityfinance

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

const signKey = "sk"

type delivery struct {
	path  string
	query url.Values
	form  url.Values
}

// gateway stands in for /v1/webhooks/unityfinance and /v1/webhooks/unityfinance/withdrawal.
func gateway(t *testing.T) (*httptest.Server, chan delivery) {
	t.Helper()
	got := make(chan delivery, 8)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		form, _ := url.ParseQuery(string(raw))
		got <- delivery{r.URL.Path, r.URL.Query(), form}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func start(t *testing.T, cfg Config) (*stubhttp.Stub, *httptest.Server) {
	t.Helper()
	stub := stubhttp.New("unityfinance")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, cfg).Routes()))
	t.Cleanup(srv.Close)
	return stub, srv
}

func config() Config {
	return Config{SignKey: signKey, PublicURL: "http://uf.test", Delay: 10 * time.Millisecond}
}

// verify re-implements unityfinance.Verify (signature.go:37-43) over the form minus ik_sign, as
// both webhook handlers build it (handler/webhook.go:339-349, withdrawal_webhook.go:108-119).
func verify(form url.Values, key string) bool {
	keys := []string{}
	for k := range form {
		if k != "ik_sign" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	vals := []string{}
	for _, k := range keys {
		vals = append(vals, form.Get(k))
	}
	sum := sha256.Sum256([]byte(strings.Join(vals, ":") + ":" + key))
	return subtle.ConstantTimeCompare([]byte(base64.StdEncoding.EncodeToString(sum[:])), []byte(form.Get("ik_sign"))) == 1
}

func control(t *testing.T, base, body string) map[string]any {
	t.Helper()
	resp, err := http.Post(base+"/__control", "application/json", strings.NewReader(body))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("control %s: %v %v", body, err, resp)
	}
	defer resp.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return m
}

// postInvoice posts what Initiate posts (adapter.go:84-176), signed with key.
func postInvoice(t *testing.T, base, gw, ref, key string) map[string]any {
	t.Helper()
	params := map[string]string{
		"ik_co_id": "co-1", "ik_pm_no": ref, "ik_am": "50.00", "ik_cur": "EUR", "ik_desc": "payment " + ref,
		"ik_payment_method": "visa", "ik_payment_currency": "EUR", "ik_act": "process", "ik_int": "json",
		"ik_ia_u": gw + "/v1/webhooks/unityfinance", "ik_suc_u": gw + "/v1/psp/checkout/" + ref + "/return",
		"ik_fal_u": gw + "/v1/psp/checkout/" + ref + "/return", "ik_customer_email": "p@example.com",
	}
	params["ik_sign"] = Sign(params, key)
	form := url.Values{}
	for k, v := range params {
		form.Set(k, v)
	}
	resp, err := http.PostForm(base, form)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("the adapter would fail to decode: %v", err)
	}
	return out
}

// withdraw posts what Payout posts (adapter.go:327-363).
func withdraw(t *testing.T, base, gw, paymentNo string, auth bool) (int, map[string]any) {
	t.Helper()
	form := url.Values{
		"amount": {"25.00"}, "purseId": {"purse-1"}, "action": {"process"}, "calcKey": {"psPayeeAmount"},
		"paymentNo": {paymentNo}, "useShortAlias": {"true"}, "method": {"eubanking"}, "currency": {"EUR"},
		"details[first_name]": {"Jo"}, "details[last_name]": {"Player"}, "details[iban]": {"DE89370400440532013000"},
		"interactionUrl": {gw + "/v1/webhooks/unityfinance/withdrawal?merchantId=m-1&brandId=b-1&terminalId=t-1"},
	}
	req, _ := http.NewRequest(http.MethodPost, base+"/v1/withdraw", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if auth {
		req.SetBasicAuth("user-1", "wd-key")
		req.Header.Set("Ik-Api-Account-Id", "acct-1")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// recoverPayout reads the status as RecoverPayout does (adapter.go:520-575).
func recoverPayout(t *testing.T, base, paymentNo string) []map[string]string {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, base+"/v1/withdraw?paymentNo="+url.QueryEscape(paymentNo), nil)
	req.SetBasicAuth("user-1", "wd-key")
	req.Header.Set("Ik-Api-Account-Id", "acct-1")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	// ufRecoverEnvelope: data an array, state a quoted number.
	var env struct {
		Status string              `json:"status"`
		Data   []map[string]string `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&env); err != nil || resp.StatusCode != http.StatusOK || env.Status != "ok" {
		t.Fatalf("status read %d %v", resp.StatusCode, err)
	}
	return env.Data
}

func await(t *testing.T, got chan delivery) delivery {
	t.Helper()
	select {
	case d := <-got:
		return d
	case <-time.After(3 * time.Second):
		t.Fatal("no webhook arrived")
		return delivery{}
	}
}

func TestHealthz(t *testing.T) {
	_, srv := start(t, config())
	if resp, err := http.Get(srv.URL + "/healthz"); err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz %v %v", resp, err)
	}
}

func TestInvoiceAnswersThePaymentFormAndSendsTheSignedWebhook(t *testing.T) {
	_, srv := start(t, config())
	gw, got := gateway(t)
	out := postInvoice(t, srv.URL, gw.URL, "txn-1", signKey)
	// adapter.go:178-205: resultCode 0 and resultData.paymentForm.action.
	data, _ := out["resultData"].(map[string]any)
	form, _ := data["paymentForm"].(map[string]any)
	if out["resultCode"] != float64(0) || !strings.HasPrefix(form["action"].(string), "http://uf.test/checkout/") || form["method"] != "get" {
		t.Fatalf("answer %v", out)
	}
	d := await(t, got)
	if d.path != "/v1/webhooks/unityfinance" || d.form.Get("ik_pm_no") != "txn-1" || d.form.Get("ik_inv_st") != "success" ||
		d.form.Get("ik_co_id") != "co-1" || !verify(d.form, signKey) {
		t.Fatalf("webhook %v", d.form)
	}
	for k := range d.form {
		if !strings.HasPrefix(k, "ik_") {
			t.Errorf("the deposit webhook carries %q, not an ik_ field", k)
		}
	}
}

func TestABadInvoiceSignatureIsError115(t *testing.T) {
	_, srv := start(t, config())
	if out := postInvoice(t, srv.URL, "http://gw", "txn-2", "another-key"); out["resultCode"] != float64(115) {
		t.Fatalf("answer %v", out)
	}
}

func TestDepositFailuresPendingAndReversal(t *testing.T) {
	_, srv := start(t, config())
	gw, got := gateway(t)

	control(t, srv.URL, `{"outcome":"failure","code":"bank_declined"}`)
	postInvoice(t, srv.URL, gw.URL, "txn-c", signKey)
	if d := await(t, got); d.form.Get("ik_inv_st") != "canceled" || d.form.Get("ik_inv_cancel_code") != "bank_declined" || !verify(d.form, signKey) {
		t.Fatalf("canceled %v", d.form)
	}
	control(t, srv.URL, `{"outcome":"failure","code":"expired"}`)
	postInvoice(t, srv.URL, gw.URL, "txn-e", signKey)
	if d := await(t, got); d.form.Get("ik_inv_st") != "expired" {
		t.Fatalf("expired %v", d.form)
	}

	control(t, srv.URL, `{"outcome":"pending"}`)
	postInvoice(t, srv.URL, gw.URL, "txn-p", signKey)
	resp, _ := http.Post(srv.URL+"/__webhook", "application/json", strings.NewReader(`{"reference":"txn-p"}`))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("webhook %d", resp.StatusCode)
	}
	if d := await(t, got); d.form.Get("ik_inv_st") != "process" {
		t.Fatalf("pending %v", d.form)
	}
	if settled := control(t, srv.URL, `{"outcome":"reversed"}`); settled["settled"] == nil {
		t.Fatalf("not settled: %v", settled)
	}
	if d := await(t, got); d.form.Get("ik_pm_no") != "txn-p" || d.form.Get("ik_inv_st") != "chargebacked" || !verify(d.form, signKey) {
		t.Fatalf("settled %v", d.form)
	}
}

func TestWithdrawAnswersTheLiveShapeAndSendsTheStateByName(t *testing.T) {
	_, srv := start(t, config())
	gw, got := gateway(t)
	status, out := withdraw(t, srv.URL, gw.URL, "wr-1", true)
	data, _ := out["data"].(map[string]any)
	wd, _ := data["withdraw"].(map[string]any)
	// ufWithdrawEnvelope.accepted (adapter.go:291-293).
	if status != http.StatusOK || out["status"] != "ok" || wd["id"] == "" || wd["id"] == nil {
		t.Fatalf("%d %v", status, out)
	}
	if rows := recoverPayout(t, srv.URL, "wr-1"); len(rows) != 1 || rows[0]["id"] != wd["id"] {
		t.Fatalf("rows %v", rows)
	}

	d := await(t, got)
	// withdrawal_webhook.go:63-73: paymentNo, id and state from the body; merchantId, brandId and
	// terminalId from the query, which the signature does not cover.
	if d.path != "/v1/webhooks/unityfinance/withdrawal" || d.query.Get("merchantId") != "m-1" || d.query.Get("brandId") != "b-1" ||
		d.form.Get("paymentNo") != "wr-1" || d.form.Get("state") != "success" || d.form.Get("id") != wd["id"] || !verify(d.form, signKey) {
		t.Fatalf("webhook %s %v %v", d.path, d.query, d.form)
	}
	if rows := recoverPayout(t, srv.URL, "wr-1"); rows[0]["state"] != "8" || rows[0]["stateName"] != "success" {
		t.Fatalf("rows %v", rows)
	}

	// A repeated paymentNo is a duplicate (adapter.go:430-436).
	if _, out := withdraw(t, srv.URL, gw.URL, "wr-1", true); out["status"] != "error" || !strings.Contains(out["message"].(string), "duplicate") {
		t.Fatalf("repeat %v", out)
	}
}

func TestEmptyBasicAuthIs401(t *testing.T) {
	_, srv := start(t, config())
	if status, out := withdraw(t, srv.URL, "http://gw", "wr-401", false); status != http.StatusUnauthorized || out["status"] != "error" {
		t.Fatalf("%d %v", status, out)
	}
	control(t, srv.URL, `{"outcome":"unauthorised"}`)
	if status, _ := withdraw(t, srv.URL, "http://gw", "wr-402", true); status != http.StatusUnauthorized {
		t.Fatalf("unauthorised answered %d", status)
	}
}

func TestStatusReadStatesAsControlled(t *testing.T) {
	_, srv := start(t, config())
	gw, got := gateway(t)
	if rows := recoverPayout(t, srv.URL, "never"); len(rows) != 0 {
		t.Fatalf("unknown paymentNo %v", rows)
	}
	cases := []struct{ control, state, name string }{
		{`{"outcome":"failure"}`, "9", "canceled"},
		{`{"outcome":"failure","code":"limitExceeded"}`, "20", "limitExceeded"},
		{`{"outcome":"reversed"}`, "11", "chargebacked"},
	}
	for i, c := range cases {
		no := "wr-s" + string(rune('a'+i))
		control(t, srv.URL, c.control)
		withdraw(t, srv.URL, gw.URL, no, true)
		if d := await(t, got); d.form.Get("state") != c.name || !verify(d.form, signKey) {
			t.Fatalf("%s: webhook %v", c.control, d.form)
		}
		if rows := recoverPayout(t, srv.URL, no); rows[0]["state"] != c.state {
			t.Fatalf("%s: rows %v", c.control, rows)
		}
	}

	control(t, srv.URL, `{"outcome":"pending"}`)
	withdraw(t, srv.URL, gw.URL, "wr-p", true)
	if rows := recoverPayout(t, srv.URL, "wr-p"); rows[0]["state"] != "1" {
		t.Fatalf("pending rows %v", rows)
	}
	control(t, srv.URL, `{"outcome":"success"}`)
	if d := await(t, got); d.form.Get("paymentNo") != "wr-p" || d.form.Get("state") != "success" {
		t.Fatalf("settled %v", d.form)
	}
	if rows := recoverPayout(t, srv.URL, "wr-p"); rows[0]["state"] != "8" {
		t.Fatalf("settled rows %v", rows)
	}

	control(t, srv.URL, `{"outcome":"no_record"}`)
	withdraw(t, srv.URL, gw.URL, "wr-n", true)
	if rows := recoverPayout(t, srv.URL, "wr-n"); len(rows) != 0 {
		t.Fatalf("no_record rows %v", rows)
	}
	if rows := recoverPayout(t, srv.URL, "wr-p"); len(rows) != 0 {
		t.Fatalf("no_record answers every read empty: %v", rows)
	}
}

func TestADuplicateControlAndTheConfiguredWebhookAddress(t *testing.T) {
	gw, got := gateway(t)
	cfg := config()
	cfg.WebhookURL = gw.URL + "/v1/webhooks/unityfinance"
	_, srv := start(t, cfg)
	withdraw(t, srv.URL, "http://unreachable.invalid", "wr-c", true)
	if d := await(t, got); d.path != "/v1/webhooks/unityfinance/withdrawal" || d.query.Get("terminalId") != "t-1" {
		t.Fatalf("webhook %s %v", d.path, d.query)
	}
	control(t, srv.URL, `{"outcome":"duplicate"}`)
	if _, out := withdraw(t, srv.URL, gw.URL, "wr-new", true); out["status"] != "error" || !strings.Contains(out["message"].(string), "already exist") {
		t.Fatalf("duplicate %v", out)
	}
	if resp, _ := http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"unsigned"}`)); resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("unsigned answered %d", resp.StatusCode)
	}
}

func TestSignMatchesTheOriginalsAlgorithm(t *testing.T) {
	// signature.go:11-35: values sorted by key ("a" < "b"), joined ":", then ":" + key.
	sum := sha256.Sum256([]byte("1:2:k"))
	if got := Sign(map[string]string{"b": "2", "a": "1"}, "k"); got != base64.StdEncoding.EncodeToString(sum[:]) {
		t.Fatalf("Sign = %s", got)
	}
}
