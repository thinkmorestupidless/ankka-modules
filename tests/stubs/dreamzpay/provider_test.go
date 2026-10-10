package main

import (
	"crypto/md5" //nolint:gosec
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/eitheror-io/ankka-casino/tests/stubs/internal/stubhttp"
)

type delivery struct {
	path string
	form url.Values
}

func gateway(t *testing.T) (*httptest.Server, chan delivery) {
	t.Helper()
	got := make(chan delivery, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		form, _ := url.ParseQuery(string(raw))
		got <- delivery{r.URL.Path, form}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func start(t *testing.T) *httptest.Server {
	t.Helper()
	stub := stubhttp.New("dreamzpay")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, Config{SecureKey: defaultSecureKey, Delay: 10 * time.Millisecond}).Routes()))
	t.Cleanup(srv.Close)
	return srv
}

// verifyInbound re-implements the gateway's check, dreamzpay/signature.go:18-26
// (InboundChecksum, VerifyInbound), used by handler/webhook.go:215 and handler/hpp.go:971.
func verifyInbound(paymentId, txnRef, amount, status, secureKey, checksum string) bool {
	data := strings.Join([]string{paymentId, txnRef, amount, status, secureKey}, "|")
	return fmt.Sprintf("%x", md5.Sum([]byte(data))) == checksum //nolint:gosec
}

// checkout posts the relay form the adapter builds (dreamzpay/adapter.go:52-77).
func checkout(t *testing.T, base, gw, txnRef string) (int, string) {
	t.Helper()
	back := gw + "/v1/psp/checkout/dreamzpay/" + txnRef + "/return?mid=sb-1&cb=x"
	form := url.Values{
		"memberId": {"e2e-test-merchant"}, "totype": {"Dreamzpay"}, "amount": {"10.00"}, "currency": {"EUR"},
		"merchantTransactionId": {txnRef}, "merchantRedirectUrl": {back}, "notificationUrl": {gw + "/v1/webhooks/dreamzpay"},
		"checksum": {OutboundChecksum("e2e-test-merchant", "Dreamzpay", "10.00", txnRef, back, defaultSecureKey)},
	}
	resp, err := http.PostForm(base+"/transaction/Checkout", form)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func await(t *testing.T, got chan delivery) delivery {
	t.Helper()
	select {
	case d := <-got:
		return d
	case <-time.After(3 * time.Second):
		t.Fatal("no notification arrived")
		return delivery{}
	}
}

func TestHealthz(t *testing.T) {
	srv := start(t)
	if resp, err := http.Get(srv.URL + "/healthz"); err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz: %v %v", resp, err)
	}
}

func TestCheckoutReturnsTheBrowserAndNotifiesSigned(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	status, page := checkout(t, srv.URL, gw.URL, "txn-1")
	// The return page posts status=Y and a checksum to the gateway's return URL (hpp.go:913-918).
	if status != http.StatusOK || !strings.Contains(page, `name="status" value="Y"`) ||
		!strings.Contains(page, "/v1/psp/checkout/dreamzpay/txn-1/return") {
		t.Fatalf("status %d page %s", status, page)
	}

	d := await(t, got)
	f := d.form
	// webhook.go:49-117 reads paymentId, merchantTransactionId, amount, transactionStatus, checksum.
	if d.path != "/v1/webhooks/dreamzpay" || f.Get("transactionStatus") != "Y" || f.Get("merchantTransactionId") != "txn-1" || f.Get("amount") != "10.00" {
		t.Fatalf("notification %s %v", d.path, f)
	}
	if !verifyInbound(f.Get("paymentId"), "txn-1", "10.00", "Y", defaultSecureKey, f.Get("checksum")) {
		t.Fatalf("the gateway would reject checksum %q", f.Get("checksum"))
	}
	if !strings.Contains(page, `value="`+f.Get("checksum")+`"`) {
		t.Fatal("the return POST and the notification carry different checksums")
	}
}

func TestControlFailureDeclines(t *testing.T) {
	srv := start(t)
	gw, got := gateway(t)
	_, _ = http.Post(srv.URL+"/__control", "application/json", strings.NewReader(`{"outcome":"failure"}`))
	_, page := checkout(t, srv.URL, gw.URL, "txn-2")
	if !strings.Contains(page, `name="status" value="N"`) {
		t.Fatalf("page %s", page)
	}
	f := await(t, got).form
	if f.Get("transactionStatus") != "N" || !verifyInbound(f.Get("paymentId"), "txn-2", "10.00", "N", defaultSecureKey, f.Get("checksum")) {
		t.Fatalf("notification %v", f)
	}
}

func TestABadOutboundChecksumIsRefused(t *testing.T) {
	stub := stubhttp.New("dreamzpay")
	srv := httptest.NewServer(stub.Handler(NewProvider(stub, Config{SecureKey: "another-key"}).Routes()))
	defer srv.Close()
	if status, _ := checkout(t, srv.URL, "http://gw.test", "txn-3"); status != http.StatusBadRequest {
		t.Fatalf("status %d", status)
	}
}
