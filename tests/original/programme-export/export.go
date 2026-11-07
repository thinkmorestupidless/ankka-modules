// Command programme-export reads one brand's affiliate twins from a local original's substrate and
// writes the bundle the programme imports (spec 013, research R19):
//
//	go run ./original/programme-export -substrate http://localhost:8080 -brand spinvibe -currency EUR \
//	    -pseudonym-key-file <the brand's attribution pseudonym key, as its store keeps it> -out bundle.json \
//	    -expected revenue.json
//
// `-expected` writes the original's own monthly revenue, per affiliate and period in minor units, which
// the programme's replay of the bundle (port/ImportParitySuite) must fold to; both files go to
// tests/port/programme/import/.
//
// Money becomes minor units, percentages basis points, statuses the programme's words, and every
// player id the pseudonym the brand's `attribution` derives from it (HMAC-SHA256 under the brand's
// pseudonym key, base64url). The key is read from the file at run time and written nowhere; spec 019
// supplies it from attribution's store, never a copy. No player id leaves this command in clear.
//
// The original keeps no currency on most facts, its payouts or its thresholds: the brand's one
// currency, `-currency`, is given them. Where the original keeps no time for a revocation or a
// deletion, the export's own time stands for it; a fact, a payout or an account with no time of its own
// is refused, never dated now. A revoked invite link is left out: its token opens nothing.
//
// A bundle of a real brand (spec 019's Spinvibe) is personal data: affiliates' identities, payment
// details and notes in clear. It is written readable by its owner alone (0600), never committed, and
// deleted once imported. Only a bundle of a local original's driver-made data goes to tests/port.
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Twin is one twin as the substrate lists it: its id's last segment and its properties.
type Twin struct {
	ID    string
	Props map[string]any
}

// Source lists a namespace's twins.
type Source interface {
	List(namespace string) ([]Twin, error)
}

// HTTPSource reads the substrate's twins API, `GET {base}/twins/{namespace}`, which takes no credential.
type HTTPSource struct {
	Base   string
	Client *http.Client
}

func (s HTTPSource) List(namespace string) ([]Twin, error) {
	resp, err := s.Client.Get(strings.TrimRight(s.Base, "/") + "/twins/" + namespace)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, nil
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("%s: %d %s", namespace, resp.StatusCode, body)
	}
	var listed struct {
		Twins []struct {
			TwinID     string         `json:"twinId"`
			Properties map[string]any `json:"properties"`
		} `json:"twins"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&listed); err != nil {
		return nil, fmt.Errorf("%s: %w", namespace, err)
	}
	twins := make([]Twin, 0, len(listed.Twins))
	for _, t := range listed.Twins {
		id := t.TwinID[strings.LastIndex(t.TwinID, "/")+1:]
		twins = append(twins, Twin{ID: id, Props: t.Properties})
	}
	sort.Slice(twins, func(i, j int) bool { return twins[i].ID < twins[j].ID })
	return twins, nil
}

func main() {
	substrate := flag.String("substrate", "http://localhost:8080", "the local original's substrate")
	brand := flag.String("brand", "spinvibe", "the brand whose affiliates move")
	currency := flag.String("currency", "EUR", "the brand's currency, given to what the original keeps without one")
	keyFile := flag.String("pseudonym-key-file", "", "the brand's pseudonym key, base64, as attribution's store keeps it")
	out := flag.String("out", "", "where the bundle is written; standard output when empty")
	expected := flag.String("expected", "", "where the original's revenue is written, per affiliate and period, for the import's replay")
	flag.Parse()
	if *keyFile == "" {
		fail(fmt.Errorf("-pseudonym-key-file is required: no player id leaves in clear"))
	}
	stored, err := os.ReadFile(*keyFile)
	fail(err)
	key, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(stored)))
	fail(err)
	src := HTTPSource{Base: *substrate, Client: &http.Client{Timeout: 60 * time.Second}}
	b, err := Export(src, *brand, *currency, Pseudonyms(key), time.Now().UTC())
	fail(err)
	w := io.Writer(os.Stdout)
	if *out != "" {
		f, err := private(*out)
		fail(err)
		defer f.Close()
		w = f
	}
	enc := json.NewEncoder(w)
	enc.SetIndent("", " ")
	fail(enc.Encode(b))
	if *expected != "" {
		r, err := Revenue(src, *brand, b.Accounts)
		fail(err)
		f, err := private(*expected)
		fail(err)
		defer f.Close()
		enc := json.NewEncoder(f)
		enc.SetIndent("", " ")
		fail(enc.Encode(r))
	}
}

// private opens a file only its owner reads: a bundle is personal data.
func private(path string) (*os.File, error) {
	return os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
}

func fail(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "programme-export:", err)
		os.Exit(1)
	}
}

// Pseudonyms derives a player's pseudonym as the brand's attribution does (its tag/Pseudonyms.derive).
func Pseudonyms(key []byte) func(string) string {
	return func(player string) string {
		mac := hmac.New(sha256.New, key)
		mac.Write([]byte(player))
		return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	}
}

// props reads a twin's properties: setProperties writes strings, incrementProperty numbers.
type props map[string]any

func (p props) str(k string) string {
	switch v := p[k].(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case json.Number:
		return v.String()
	}
	return ""
}

func (p props) opt(k string) *string {
	if v := p.str(k); v != "" {
		return &v
	}
	return nil
}

func (p props) at(k string) (*time.Time, error) {
	v := p.str(k)
	if v == "" {
		return nil, nil
	}
	t, err := time.Parse(time.RFC3339Nano, v)
	if err != nil {
		return nil, fmt.Errorf("%s %q: %w", k, v, err)
	}
	t = t.UTC()
	return &t, nil
}

// exponents are the currencies whose minor unit is not a hundredth (ISO 4217); every other has two places.
var exponents = map[string]int64{
	"BIF": 0, "CLP": 0, "DJF": 0, "GNF": 0, "ISK": 0, "JPY": 0, "KMF": 0, "KRW": 0, "PYG": 0, "RWF": 0, "UGX": 0, "UYI": 0,
	"VND": 0, "VUV": 0, "XAF": 0, "XOF": 0, "XPF": 0,
	"BHD": 3, "IQD": 3, "JOD": 3, "KWD": 3, "LYD": 3, "OMR": 3, "TND": 3,
}

// minorIn is a decimal major-unit amount in the currency's minor units; more places than it has is refused,
// not rounded, as is an amount past an int64.
func minorIn(v, currency string) (int64, error) {
	places, ok := exponents[strings.ToUpper(currency)]
	if !ok {
		places = 2
	}
	return scaled(v, places)
}

// minor is a decimal in hundredths: a percentage's basis points, or a two-place currency.
func minor(v string) (int64, error) { return scaled(v, 2) }

func scaled(v string, places int64) (int64, error) {
	if strings.TrimSpace(v) == "" {
		return 0, nil
	}
	r, ok := new(big.Rat).SetString(strings.TrimSpace(v))
	if !ok {
		return 0, fmt.Errorf("%q is not an amount", v)
	}
	r.Mul(r, new(big.Rat).SetInt(new(big.Int).Exp(big.NewInt(10), big.NewInt(places), nil)))
	if !r.IsInt() {
		return 0, fmt.Errorf("%q has more than %d decimal places", v, places)
	}
	if !r.Num().IsInt64() {
		return 0, fmt.Errorf("%q is past an int64 in minor units", v)
	}
	return r.Num().Int64(), nil
}

// bp is a decimal percentage in basis points (12.5 is 1250).
func bp(v string) (int, error) {
	n, err := minor(v)
	return int(n), err
}
