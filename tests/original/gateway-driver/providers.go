package gatewaydriver

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/eitheror-io/ankka-casino/tests/stubs/bouncemoney"
	"github.com/eitheror-io/ankka-casino/tests/stubs/digitarium"
	"github.com/eitheror-io/ankka-casino/tests/stubs/dreamzpay"
	"github.com/eitheror-io/ankka-casino/tests/stubs/eupaymentz"
	"github.com/eitheror-io/ankka-casino/tests/stubs/t365"
	"github.com/eitheror-io/ankka-casino/tests/stubs/unityfinance"
)

// Provider is one of psp-stub's nine, as the original's gateway is driven against it: the name the
// original's initiation takes (`psp`), the stub's port, a deposit the provider accepts, the terminal
// properties carrying the stub's credentials (each provider's README under tests/stubs/), and how the
// driver writes a webhook the stub would not send.
type Provider struct {
	Name     string // psp-stub's name, and the scenario's suffix
	PSP      string // the original's provider name on the initiation
	Port     string
	Currency string
	Method   string
	Amount   int64 // minor units
	// Properties are the terminal's, as merchant-service sends them in `terminalProperties` and
	// serves them from its terminal read.
	Properties map[string]string
	// Rotate is a property the stub does not check, changed by the rotation scenario so the provider
	// call shows the value read at that moment.
	Rotate string
	// Return is the browser's return route, with {reference}; empty for a crypto provider, which has none.
	Return string
	// Webhook is the gateway's webhook route for a deposit.
	Webhook string
	// Crypto is whether the answer is an address and a coin amount rather than a redirect.
	Crypto bool
	// Payout is the provider's payout route and request, nil for the five without payouts.
	Payout *Payout
	forge  func(s *Session, p Provider, f Forgery) Hook
}

// Payout is how the original's gateway pays out through a provider (internal/handler/payout.go,
// internal/handler/gigadat.go): its route, its request, and what psp-stub knows the payout by.
type Payout struct {
	Route string
	// Body is the request for the withdrawal request minted under `request`.
	Body func(s *Session, request string) map[string]any
	// Reference is the value psp-stub's /__webhook takes for the payout under `request`.
	Reference func(s *Session, request string) string
	// Outcomes are the webhook outcomes the provider reports for a payout: the outline's examples it has.
	Outcomes []string
}

// Forgery is a webhook the driver writes: the provider's shape for the scenario's session, changed.
type Forgery struct {
	Status    string            // the provider's own status word; empty is its success
	Amount    string            // the provider's rendering of the amount; empty is the session's
	Reference string            // empty is the session's reference
	Unsigned  bool              // signed under another key, or for Morphosis sent to another token
	Fields    map[string]string // fields set over the provider's
	Drop      []string          // fields left out
}

// Hook is a webhook ready to post to the gateway.
type Hook struct {
	Path    string
	Body    Body
	Headers map[string]string
}

// The stubs' defaults: each provider README's credentials, which the terminal must carry.
const (
	dreamzpayKey    = "dreamzpay-test-key"
	eupaymentzKey   = "sk-test"
	unityfinanceKey = "unityfinance-signing-secret"
	t365Key         = "t365-stub-secret"
	bouncemoneyKey  = "test-signing-secret-unit-only"
	digitariumKey   = "test-signing-key-unit-only"
	wrongKey        = "not-the-providers-key"
)

// bouncemoneyPrivateKey is an Ed25519 key made from a fixed, published seed: a test key, the same on
// every run, as the adapter's request signing needs one (bouncemoney/adapter.go:107-114). The stub
// verifies nothing unless given the public key.
func bouncemoneyPrivateKey(seed byte) string {
	s := make([]byte, ed25519.SeedSize)
	for i := range s {
		s[i] = seed
	}
	der, _ := x509.MarshalPKCS8PrivateKey(ed25519.NewKeyFromSeed(s))
	return string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
}

// twoDecimals is `%.2f` of minor units, as DreamzPay, Unity Finance, T365 and Morphosis render it.
func twoDecimals(minor int64) string { return fmt.Sprintf("%d.%02d", minor/100, minor%100) }

func (s *Session) reference(f Forgery) string {
	if f.Reference != "" {
		return f.Reference
	}
	return s.Value("reference")
}

func apply(m map[string]any, f Forgery) map[string]any {
	for k, v := range f.Fields {
		m[k] = v
	}
	for _, k := range f.Drop {
		delete(m, k)
	}
	return m
}

func applyForm(v url.Values, f Forgery) url.Values {
	for k, x := range f.Fields {
		v.Set(k, x)
	}
	for _, k := range f.Drop {
		v.Del(k)
	}
	return v
}

func or(v, fallback string) string {
	if v != "" {
		return v
	}
	return fallback
}

// Providers are psp-stub's nine (tests/stubs/psp-stub/README.md).
var Providers = []Provider{
	{
		Name: "bouncemoney", PSP: "BOUNCEMONEY", Port: "18101", Currency: "EUR", Method: "CARD", Amount: 5000,
		Properties: map[string]string{"keyId": "stub-key-1", "privateKey": bouncemoneyPrivateKey(1), "signingSecret": bouncemoneyKey},
		Rotate:     "keyId",
		Return:     "/v1/psp/checkout/bouncemoney/{reference}/return",
		Webhook:    "/v1/webhooks/bouncemoney",
		// {"id","type","created","data":{"id","referenceId","status",…}}, X-BounceMoney-Signature
		// t=…,v1=… over `t.body` (bouncemoney/signature.go:43-63; the stub's webhookBody)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			typ := "checkout.session." + or(f.Status, "completed")
			data := apply(map[string]any{"id": "cs_forged", "referenceId": s.reference(f), "status": or(f.Status, "completed"),
				"fiatAmount": or(f.Amount, twoDecimals(p.Amount)), "fiat": p.Currency}, f)
			ts := s.cfg.Now().Unix()
			body := JSON(map[string]any{"id": "evt_forged", "type": typ, "created": ts, "data": data})
			key := bouncemoneyKey
			if f.Unsigned {
				key = wrongKey
			}
			return Hook{Path: p.Webhook, Body: body, Headers: map[string]string{"X-BounceMoney-Signature": bouncemoney.Sign(key, ts, body.Bytes)}}
		},
	},
	{
		Name: "cozeni", PSP: "COZENI", Port: "18102", Currency: "EUR", Method: "BTC", Amount: 5000, Crypto: true,
		Properties: map[string]string{"accountId": "stub-account", "apiKey": "stub-cozeni-key", "merchantId": "stub-merchant"},
		Rotate:     "accountId",
		Webhook:    "/v1/webhooks/cozeni",
		// The form callback, txnauthz the token the gateway sent on the pay-in (the stub echoes it;
		// cozeni/signature.go:9-31), which the driver reads from psp-stub's record of that request.
		forge: func(s *Session, p Provider, f Forgery) Hook {
			token := s.fact("txnauthz")
			if f.Unsigned {
				token = strings.Repeat("0", 64)
			}
			form := url.Values{
				"txnId": {"100000001"}, "paymentRef": {s.reference(f)}, "txnauthz": {token},
				"requestAmount": {s.Value("cryptoAmount")}, "requestCurrency": {p.Method},
				"status": {or(f.Status, "COMPLETED")}, "resultCode": {"00"}, "resultMsg": {"Success"},
				"receivedAmount": {or(f.Amount, s.Value("cryptoAmount"))}, "convertAmount": {twoDecimals(p.Amount)}, "convertCurrency": {p.Currency},
				"txnHash": {"0xforged"},
			}
			return Hook{Path: p.Webhook, Body: Form(applyForm(form, f))}
		},
		Payout: &Payout{
			Route: "/v1/psp/payout/cozeni",
			Body: func(s *Session, request string) map[string]any {
				return map[string]any{"merchantId": s.ID("subBrand"), "brandId": s.ID("brand"), "withdrawalRequestId": s.ID(request),
					"amount": "50.00", "currency": "EUR", "cryptoSymbol": "BTC", "walletAddress": "bc1qdriverpayoutaddress", "terminalId": s.ID("terminal")}
			},
			Reference: func(s *Session, request string) string { return s.Value(request) },
			Outcomes:  []string{"completed", "failed"},
		},
	},
	{
		Name: "dreamzpay", PSP: "DREAMZPAY", Port: "18103", Currency: "EUR", Method: "VISA", Amount: 5000,
		Properties: map[string]string{"memberId": "stub-member", "totype": "stub-totype", "secureKey": dreamzpayKey, "terminalId": "stub-terminal"},
		Rotate:     "memberId",
		Return:     "/v1/psp/checkout/dreamzpay/{reference}/return",
		Webhook:    "/v1/webhooks/dreamzpay",
		// The form notification, checksum MD5(paymentId|ref|amount|transactionStatus|secureKey)
		// (dreamzpay/signature.go:16-26)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			status, amount, key := or(f.Status, "Y"), or(f.Amount, twoDecimals(p.Amount)), dreamzpayKey
			if f.Unsigned {
				key = wrongKey
			}
			form := url.Values{"paymentId": {"10000001"}, "merchantTransactionId": {s.reference(f)}, "amount": {amount},
				"currency": {p.Currency}, "status": {status}, "transactionStatus": {status},
				"checksum": {dreamzpay.InboundChecksum("10000001", s.reference(f), amount, status, key)}}
			return Hook{Path: p.Webhook, Body: Form(applyForm(form, f))}
		},
	},
	{
		Name: "eupaymentz", PSP: "EUPAYMENTZ", Port: "18104", Currency: "EUR", Method: "CARD", Amount: 5000,
		Properties: map[string]string{"accountId": "stub-account", "password": "stub-password", "passphrase": eupaymentzKey},
		Rotate:     "accountId",
		Return:     "/v1/psp/checkout/eupaymentz/{reference}/return",
		Webhook:    "/v1/webhooks/eupaymentz",
		// The flat JSON callback, resp_sha SHA-256(passphrase + resp_trans_id + amount + status); the
		// lookup field, resp_trans_merchant_id, is outside it (eupaymentz/signature.go:23-26)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			status, amount, key := or(f.Status, "00000"), or(f.Amount, twoDecimals(p.Amount)), eupaymentzKey
			if f.Unsigned {
				key = wrongKey
			}
			return Hook{Path: p.Webhook, Body: JSON(apply(map[string]any{
				"resp_trans_id": "forged-trans", "resp_trans_merchant_id": s.reference(f), "resp_trans_amount": amount,
				"resp_action_type": "payment", "resp_trans_status": status, "resp_trans_description_status": "Transaction approved",
				"resp_sha": eupaymentz.Sign(key, "forged-trans", amount, status), "resp_descriptor": "-",
			}, f))}
		},
	},
	{
		Name: "gigadat", PSP: "GIGADAT", Port: "18105", Currency: "CAD", Method: "INTERAC", Amount: 5000,
		Properties: map[string]string{"campaignId": "stub-campaign", "accessToken": "stub-access", "securityToken": "stub-security",
			"siteUrl": "https://merchant.example", "environment": "sandbox"},
		Rotate:  "campaignId",
		Return:  "/v1/psp/checkout/gigadat/{reference}/return",
		Webhook: "/v1/webhooks/gigadat",
		// ?transactionId=&status= with a JSON array of {key,value}; Gigadat signs nothing and the
		// original checks nothing (handler/gigadat.go:83-86), so a forgery differs only in its cookie
		forge: func(s *Session, p Provider, f Forgery) Hook {
			cookie := "stub-cookie"
			if f.Unsigned {
				cookie = "forged-cookie"
			}
			pairs := []map[string]string{{"key": "transactionId", "value": s.reference(f)}, {"key": "type", "value": "CPI"},
				{"key": "amount", "value": or(f.Amount, strconv.FormatInt(p.Amount/100, 10))}, {"key": "cookie", "value": cookie},
				{"key": "rcode", "value": "100"}}
			q := url.Values{"transactionId": {s.reference(f)}, "status": {or(f.Status, "STATUS_SUCCESS")}}
			return Hook{Path: p.Webhook + "?" + q.Encode(), Body: JSON(pairs)}
		},
		Payout: &Payout{
			Route: "/v1/psp/payout/gigadat",
			Body: func(s *Session, request string) map[string]any {
				// The original takes this route's amount in cents, as a number (handler/gigadat.go:168),
				// where the other three take a decimal string.
				return map[string]any{"merchantId": s.ID("subBrand"), "brandId": s.ID("brand"), "withdrawalRequestId": s.ID(request),
					"customerId": s.ID("player"), "amount": 5000, "currency": "CAD", "payoutMethod": "ETO", "email": "player@example.com",
					"mobile": "4165550100", "firstName": "Pat", "lastName": "Player", "ip": "127.0.0.1", "terminalId": s.ID("terminal")}
			},
			Reference: func(s *Session, request string) string { return s.Value(request) },
			Outcomes:  []string{"completed", "failed"},
		},
	},
	{
		Name: "morphosis", PSP: "MORPHOSIS", Port: "18106", Currency: "USD", Method: "BTC", Amount: 5000, Crypto: true,
		Properties: map[string]string{"user": "stub-user", "pass": "stub-pass", "account": "stub-account"},
		Rotate:     "account",
		Webhook:    "/v1/webhooks/morphosis/deposit/",
		// QVEI's JSON callback to the notification address, which carries the session's token; the
		// gateway admits it on that token and the id insert_deposit answered (handler/morphosis.go)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			token := s.fact("notifyToken")
			if f.Unsigned || token == "" { // no session's token (a webhook for an unknown reference), or another's
				token = "00000000-0000-4000-8000-000000000000"
			}
			body := apply(map[string]any{"id": s.fact("wpbId"), "status": or(f.Status, "ac"), "tx_hash": "stub-tx-forged",
				"coin_amount": or(f.Amount, s.Value("cryptoAmount"))}, f)
			return Hook{Path: p.Webhook + token, Body: JSON(body)}
		},
		Payout: &Payout{
			Route: "/v1/psp/payout/morphosis",
			Body: func(s *Session, request string) map[string]any {
				return map[string]any{"merchantId": s.ID("subBrand"), "brandId": s.ID("brand"), "withdrawalRequestId": s.ID(request),
					"amount": "50.00", "currency": "USD", "cryptoSymbol": "BTC", "walletAddress": "bc1qdriverpayoutaddress", "details": "", "terminalId": s.ID("terminal")}
			},
			// The request id is not sent to QVEI; psp-stub knows the payout by its notify token.
			Reference: func(s *Session, request string) string { return s.fact("payoutToken") },
			Outcomes:  []string{"completed", "failed"},
		},
	},
	{
		Name: "t365", PSP: "T365", Port: "18107", Currency: "EUR", Method: "BANK_TRANSFER", Amount: 5000,
		Properties: map[string]string{"apiKey": "stub-t365-key", "secretKey": t365Key, "midId": "1001"},
		Rotate:     "midId",
		Return:     "/v1/psp/checkout/t365/{reference}/return",
		Webhook:    "/v1/webhooks/t365",
		// {"id","orderId","status","amount","currency","hash"}, hash over the dashless orderId
		// (t365/signature.go:30-38)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			order := strings.ReplaceAll(s.reference(f), "-", "")
			status, amount, key := or(f.Status, "SUCCESS"), or(f.Amount, twoDecimals(p.Amount)), t365Key
			if f.Unsigned {
				key = wrongKey
			}
			return Hook{Path: p.Webhook, Body: JSON(apply(map[string]any{"id": "forged-t365", "orderId": order, "status": status,
				"amount": amount, "currency": p.Currency, "hash": t365.Sign(key, amount, p.Currency, order, status)}, f))}
		},
	},
	{
		Name: "unityfinance", PSP: "UNITYFINANCE", Port: "18108", Currency: "EUR", Method: "VISA", Amount: 5000,
		Properties: map[string]string{"checkoutId.VISA": "stub-checkout", "signKey": unityfinanceKey,
			"userId": "stub-user", "withdrawalApiKey": "stub-withdrawal-key", "accountId": "stub-account", "purseId": "stub-purse"},
		Rotate:  "checkoutId.VISA",
		Return:  "/v1/psp/checkout/{reference}/return",
		Webhook: "/v1/webhooks/unityfinance",
		// The ik_* form, ik_sign over every other field (unityfinance/signature.go:11-35); ik_co_id must
		// be the session's checkout id (handler/webhook.go:351-355)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			key := unityfinanceKey
			if f.Unsigned {
				key = wrongKey
			}
			form := applyForm(url.Values{"ik_co_id": {"stub-checkout"}, "ik_pm_no": {s.reference(f)}, "ik_inv_id": {"300001"},
				"ik_inv_st": {or(f.Status, "success")}, "ik_inv_crt": {s.cfg.Now().UTC().Format("2006-01-02 15:04:05")},
				"ik_am": {or(f.Amount, twoDecimals(p.Amount))}, "ik_cur": {p.Currency}}, f)
			params := map[string]string{}
			for k := range form {
				params[k] = form.Get(k)
			}
			form.Set("ik_sign", unityfinance.Sign(params, key))
			return Hook{Path: p.Webhook, Body: Form(form)}
		},
		Payout: &Payout{
			Route: "/v1/psp/payout/unityfinance",
			Body: func(s *Session, request string) map[string]any {
				return map[string]any{"merchantId": s.ID("subBrand"), "brandId": s.ID("brand"), "withdrawalRequestId": s.ID(request),
					"amount": "50.00", "currency": "EUR", "firstName": "Pat", "lastName": "Player", "email": "player@example.com",
					"iban": "GB33BUKB20201555555555", "terminalId": s.ID("terminal")}
			},
			Reference: func(s *Session, request string) string { return s.Value(request) },
			Outcomes:  []string{"completed", "failed", "reversed"},
		},
	},
	{
		Name: "digitarium", PSP: "DIGITARIUM", Port: "18109", Currency: "EUR", Method: "CARD", Amount: 5000,
		Properties: map[string]string{"apiKey": "stub-digitarium-key", "signingKey": digitariumKey, "environment": "sandbox"},
		Rotate:     "apiKey",
		Return:     "/v1/psp/checkout/digitarium/{reference}/return",
		Webhook:    "/v1/webhooks/digitarium",
		// The flat payment object, Signature hex HMAC-SHA256 of the raw body (digitarium/signature.go:21-52)
		forge: func(s *Session, p Provider, f Forgery) Hook {
			amount := float64(p.Amount) / 100
			if f.Amount != "" {
				amount, _ = strconv.ParseFloat(f.Amount, 64)
			}
			body := JSON(apply(map[string]any{"id": "forged-g2pay", "referenceId": s.reference(f), "paymentType": "DEPOSIT",
				"state": or(f.Status, "COMPLETED"), "amount": amount, "currency": p.Currency}, f))
			key := digitariumKey
			if f.Unsigned {
				key = wrongKey
			}
			sig, _ := digitarium.Signature(key, body.Bytes, "hex")
			return Hook{Path: p.Webhook, Body: body, Headers: map[string]string{"Signature": sig}}
		},
	},
}

// ProviderNamed is one of Providers.
func ProviderNamed(name string) Provider {
	for _, p := range Providers {
		if p.Name == name {
			return p
		}
	}
	panic("no provider " + name)
}

// tampered is ten times the session's amount, in the provider's rendering.
func tampered(p Provider) string {
	if p.Name == "gigadat" {
		return strconv.FormatInt(p.Amount/10, 10)
	}
	return twoDecimals(p.Amount * 10)
}

// rotated is a property set with Rotate changed.
func rotated(p Provider) map[string]string {
	out := map[string]string{}
	for k, v := range p.Properties {
		out[k] = v
	}
	out[p.Rotate] = p.Properties[p.Rotate] + "-rotated"
	return out
}
