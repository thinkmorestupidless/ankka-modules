package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

var (
	track, app *httptest.Server
	stub       *Stub
)

func TestMain(m *testing.M) {
	stub = New(Config{SiteID: "site", TrackKey: "tk", AppKey: "ak", Hang: 2 * time.Second})
	track, app = httptest.NewServer(stub.Track()), httptest.NewServer(stub.App())
	code := m.Run()
	track.Close()
	app.Close()
	os.Exit(code)
}

func reset(t *testing.T) {
	t.Helper()
	if status, _ := do(t, http.DefaultClient, "POST", track.URL+"/__control", `{"mode":"reset"}`, nil); status != 200 {
		t.Fatalf("reset answered %d", status)
	}
}

func do(t *testing.T, c *http.Client, method, url, body string, auth func(*http.Request)) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(method, url, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if auth != nil {
		auth(req)
	}
	r, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(r.Body).Decode(&m)
	return r.StatusCode, m
}

func basic(id, key string) func(*http.Request) {
	return func(r *http.Request) { r.SetBasicAuth(id, key) }
}
func bearer(key string) func(*http.Request) {
	return func(r *http.Request) { r.Header.Set("Authorization", "Bearer "+key) }
}

const identify = `{"type":"person","action":"identify","identifiers":{"id":"p1"},"attributes":{"brand":"goldvibe"}}`
const email = `{"transactional_message_id":"7","identifiers":{"id":"p1"},"to":"a@b.c"}`

func entity(t *testing.T, auth func(*http.Request)) (int, map[string]any) {
	return do(t, http.DefaultClient, "POST", track.URL+"/api/v2/entity", identify, auth)
}

func send(t *testing.T, auth func(*http.Request)) (int, map[string]any) {
	return do(t, http.DefaultClient, "POST", app.URL+"/v1/send/email", email, auth)
}

func TestTrackAuth(t *testing.T) {
	reset(t)
	if status, body := entity(t, basic("site", "tk")); status != 200 || len(body) != 0 {
		t.Fatalf("the right credentials answered %d %v", status, body)
	}
	for _, auth := range []func(*http.Request){nil, basic("site", "wrong"), basic("other", "tk"), bearer("tk")} {
		if status, _ := entity(t, auth); status != 401 {
			t.Fatalf("wrong credentials answered %d", status)
		}
	}
}

func TestAppAuth(t *testing.T) {
	reset(t)
	status, body := send(t, bearer("ak"))
	if status != 200 || body["delivery_id"] == "" || body["delivery_id"] == nil || body["queued_at"] == nil {
		t.Fatalf("the right key answered %d %v", status, body)
	}
	_, again := send(t, bearer("ak"))
	if again["delivery_id"] == body["delivery_id"] {
		t.Fatalf("two sends had one delivery_id")
	}
	for _, auth := range []func(*http.Request){nil, bearer("wrong"), basic("site", "tk")} {
		if status, _ := send(t, auth); status != 401 {
			t.Fatalf("a wrong key answered %d", status)
		}
	}
}

func TestReceivedInOrderAcrossHosts(t *testing.T) {
	reset(t)
	entity(t, basic("site", "tk"))
	send(t, bearer("ak"))
	entity(t, nil)
	for _, base := range []string{track.URL, app.URL} {
		r, err := http.Get(base + "/__received")
		if err != nil {
			t.Fatal(err)
		}
		var got []Received
		_ = json.NewDecoder(r.Body).Decode(&got)
		r.Body.Close()
		if len(got) != 3 {
			t.Fatalf("the log has %d calls: %+v", len(got), got)
		}
		want := []struct{ host, path string }{{"track", "/api/v2/entity"}, {"app", "/v1/send/email"}, {"track", "/api/v2/entity"}}
		for i, w := range want {
			if got[i].Host != w.host || got[i].Path != w.path || got[i].Method != "POST" {
				t.Fatalf("call %d is %+v", i, got[i])
			}
		}
		if string(got[0].Body) != identify || got[1].Header["Authorization"] != "Bearer ak" {
			t.Fatalf("the body or headers were not kept: %s %v", got[0].Body, got[1].Header)
		}
		if _, ok := got[2].Header["Authorization"]; ok {
			t.Fatalf("a call without credentials logged some")
		}
	}
}

func TestScriptedStatusesThenOK(t *testing.T) {
	for _, mode := range []string{"429", "500", "503"} {
		reset(t)
		if status, _ := do(t, http.DefaultClient, "POST", app.URL+"/__control", `{"mode":"`+mode+`","count":2}`, nil); status != 200 {
			t.Fatalf("control answered %d", status)
		}
		// The script spans both hosts: one call on each takes it.
		if status, _ := entity(t, basic("site", "tk")); status != must(strconv.Atoi(mode)) {
			t.Fatalf("%s: the first call answered %d", mode, status)
		}
		if status, _ := send(t, bearer("ak")); status != must(strconv.Atoi(mode)) {
			t.Fatalf("%s: the second call answered %d", mode, status)
		}
		if status, _ := entity(t, basic("site", "tk")); status != 200 {
			t.Fatalf("%s: the third call answered %d", mode, status)
		}
		if n := len(stub.Received()); n != 3 {
			t.Fatalf("%s: the log has %d calls", mode, n)
		}
	}
}

func TestHangUntilTheClientGivesUp(t *testing.T) {
	reset(t)
	do(t, http.DefaultClient, "POST", track.URL+"/__control", `{"mode":"hang"}`, nil)
	short := &http.Client{Timeout: 200 * time.Millisecond}
	req, _ := http.NewRequest("POST", track.URL+"/api/v2/entity", strings.NewReader(identify))
	req.SetBasicAuth("site", "tk")
	start := time.Now()
	_, err := short.Do(req)
	var ne interface{ Timeout() bool }
	if err == nil || !errors.As(err, &ne) || !ne.Timeout() {
		t.Fatalf("the hang answered: %v", err)
	}
	if time.Since(start) > time.Second {
		t.Fatalf("the client waited %s", time.Since(start))
	}
	if status, _ := entity(t, basic("site", "tk")); status != 200 {
		t.Fatalf("the call after the hang answered %d", status)
	}
}

func TestHangEndsWith504(t *testing.T) {
	reset(t)
	do(t, http.DefaultClient, "POST", app.URL+"/__control", `{"mode":"hang"}`, nil)
	if status, _ := send(t, bearer("ak")); status != 504 {
		t.Fatalf("a hang past its hold answered %d", status)
	}
}

func TestControlRefusesAnUnknownMode(t *testing.T) {
	if status, _ := do(t, http.DefaultClient, "POST", track.URL+"/__control", `{"mode":"418"}`, nil); status != 400 {
		t.Fatalf("an unknown mode answered %d", status)
	}
}

func TestResetClearsScriptAndLog(t *testing.T) {
	reset(t)
	entity(t, basic("site", "tk"))
	do(t, http.DefaultClient, "POST", track.URL+"/__control", `{"mode":"500","count":3}`, nil)
	reset(t)
	if n := len(stub.Received()); n != 0 {
		t.Fatalf("the log kept %d calls", n)
	}
	if status, _ := entity(t, basic("site", "tk")); status != 200 {
		t.Fatalf("the script survived the reset: %d", status)
	}
}

func must(n int, _ error) int { return n }
