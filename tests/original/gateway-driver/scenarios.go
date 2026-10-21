package gatewaydriver

import (
	"net/http"
	"net/url"
	"time"
)

// Def is one scenario the driver records: a scenario of one of the four features, for one provider
// where the feature is per provider (research R16).
type Def struct {
	Feature     string // the feature file under features/payments/
	Name        string // the scenario as its feature names it, an outline's example in brackets
	Provider    string // the suffix; empty where the feature is not per provider
	Requirement string
	Run         func(s *Session)
}

// Scenario is the recording's name: the feature's, with the provider as a suffix (`…/cozeni`).
func (d Def) Scenario() string {
	if d.Provider == "" {
		return d.Name
	}
	return d.Name + "/" + d.Provider
}

// NotDriven are the feature scenarios no recording of the original can show, and why. Each is held
// by the port's own suites instead.
var NotDriven = map[string]string{
	"no credential value and no personal data of a player appears in what is logged or traced": "" +
		"a property of the gateway's logs and traces, which a recording of its answers does not hold",
}

// Scenarios is every scenario, in the features' order.
func Scenarios() []Def {
	var out []Def
	out = append(out, providerScenarios()...)
	out = append(out, cryptoScenarios()...)
	out = append(out, payoutScenarios()...)
	out = append(out, outcomeScenarios()...)
	return out
}

const (
	providersFeature = "providers.feature"
	cryptoFeature    = "crypto.feature"
	payoutsFeature   = "withdrawal-payments.feature"
	outcomesFeature  = "outcomes.feature"
)

// per makes one Def per provider that `has`.
func per(feature, name, requirement string, has func(Provider) bool, run func(s *Session, p Provider)) []Def {
	var out []Def
	for _, p := range Providers {
		if has != nil && !has(p) {
			continue
		}
		p := p
		out = append(out, Def{Feature: feature, Name: name, Provider: p.Name, Requirement: requirement, Run: func(s *Session) { run(s, p) }})
	}
	return out
}

func returns(p Provider) bool { return p.Return != "" }
func pays(p Provider) bool    { return p.Payout != nil }

// open is the webhook control a provider's stub sends a status that is not final with; DreamzPay has
// none: the original reads every status but Y as a failure (handler/webhook.go:220-235).
func open(p Provider) bool { return p.Name != "dreamzpay" }

// features/payments/providers.feature, per provider.
func providerScenarios() []Def {
	var out []Def
	out = append(out, per(providersFeature, "a deposit initiated for a sub-brand with a credential answers the provider's reference and where to send the player, and records a provider session", "005/FR-003", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		// The same payment id again: the port answers the first answer and calls no provider (FR-008);
		// the original mints a second reference and opens a second provider session (research R3).
		s.Initiate("initiate again under the same payment id", p, "payment", "secondReference", p.Properties)
		if s.Value("secondReference") != "" {
			s.Reach(p, "secondReference")
		}
	})...)
	out = append(out, per(providersFeature, "a deposit initiated for a sub-brand with no credential for the provider is refused naming both, and no provider is called", "005/FR-018", nil, func(s *Session, p Provider) {
		s.UseWith(p, map[string]string{})
		s.Want(s.Initiate("initiate with a terminal carrying no credential", p, "payment", "reference", map[string]string{}), http.StatusNotFound)
		s.ProviderCalls(p, 0)
	})...)
	out = append(out, per(providersFeature, "a verified webhook reporting success tells the cashier the payment is captured, once, and the provider session records it delivered", "005/FR-014", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Webhook("success", p, s.Value("reference"), map[string]any{"outcome": "success"}), 200)
		s.Outcomes("the capture", 1)
	})...)
	out = append(out, per(providersFeature, "a webhook reporting a failure tells the cashier the payment failed with the provider's code and a reason a player can read", "005/FR-005", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Webhook("failure", p, s.Value("reference"), map[string]any{"outcome": "failure"}), 200)
		s.Outcomes("the failure", 1)
	})...)
	out = append(out, per(providersFeature, "a webhook whose signature does not verify is refused as a bad request, logged, and moves nothing", "005/FR-010", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		// A bad request is answered 400 by every provider that signs (FR-011, decided 2026-10-19); Gigadat
		// does not sign and Morphosis is trusted by its address, so both are acknowledged.
		refused := 400
		if p.Name == "gigadat" || p.Name == "morphosis" {
			refused = 200
		}
		s.Want(s.Forged("signed under another key", p, Forgery{Unsigned: true}), refused)
		// A webhook whose amount differs from the session's is refused as not verifying (spec, edge
		// cases): signed under the right key, for ten times the amount.
		s.Want(s.Forged("the amount tampered", p, Forgery{Amount: tampered(p)}), 200)
		if p.Name == "bouncemoney" || p.Name == "digitarium" {
			s.WantRelayed(s.Webhook("unsigned", p, s.Value("reference"), map[string]any{"outcome": "unsigned"}), refused)
		}
		s.Outcomes("no outcome", 0)
	})...)
	out = append(out, per(providersFeature, "a webhook for a reference the gateway does not know is acknowledged and moves nothing", "005/FR-011", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Want(s.Forged("for unknown-ref", p, Forgery{Reference: "unknown-ref"}), 200)
		s.Outcomes("no outcome", 0)
	})...)
	out = append(out, per(providersFeature, "the same webhook arriving again for a captured provider session is acknowledged and the cashier is not told twice", "005/FR-014", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Webhook("success", p, s.Value("reference"), map[string]any{"outcome": "success"}), 200)
		s.Want(s.Webhook("the same success again", p, s.Value("reference"), map[string]any{"outcome": "success"}), 200)
		s.Outcomes("the capture, once", 1)
	})...)
	out = append(out, per(providersFeature, "a webhook reporting a status that is not final leaves the provider session open and the cashier untold", "005/FR-005", open, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Webhook("a status that is not final", p, s.Value("reference"), map[string]any{"outcome": "pending"}), 200)
		s.Outcomes("no outcome", 0)
	})...)
	out = append(out, per(providersFeature, "a deposit is initiated with the credential read from the secret store at that moment", "005/FR-018", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
	})...)
	out = append(out, per(providersFeature, "a rotated credential is used by the next deposit with no restart", "005/FR-018", nil, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		next := rotated(p)
		s.Terminal(next)
		s.Want(s.Initiate("the next deposit, after the credential is rotated", p, "payment2", "reference2", next), 200)
		s.Reach(p, "reference2")
	})...)
	out = append(out, per(providersFeature, "a provider's private signing key is read and parsed at request time and never cached across rotations", "005/FR-018",
		func(p Provider) bool { return p.Name == "bouncemoney" }, func(s *Session, p Provider) {
			// BounceMoney is the one provider whose requests are signed with a private key (Ed25519).
			s.Use(p)
			s.Deposit(p)
			next := map[string]string{}
			for k, v := range p.Properties {
				next[k] = v
			}
			next["privateKey"] = bouncemoneyPrivateKey(2)
			s.Terminal(next)
			s.Want(s.Initiate("the next deposit, after the signing key is rotated", p, "payment2", "reference2", next), 200)
			s.Reach(p, "reference2")
		})...)
	out = append(out, per(providersFeature, "a player's browser returning from the payment provider is shown that the payment is being processed and settles nothing", "005/US6", returns, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Return("the player's browser returns", p, s.Value("reference"), http.MethodGet, nil), 200)
		if p.Name == "dreamzpay" {
			// DreamzPay's browser return is a self-posting form carrying the outcome and its checksum;
			// the original settles the payment on it (research, *Findings* 5).
			f := p.forge(s, p, Forgery{})
			form, _ := url.ParseQuery(string(f.Body.Bytes))
			ret := url.Values{"paymentId": {form.Get("paymentId")}, "merchantTransactionId": {form.Get("merchantTransactionId")},
				"amount": {form.Get("amount")}, "currency": {form.Get("currency")}, "status": {"Y"}, "checksum": {form.Get("checksum")}}
			s.Want(s.Return("the player's browser posts the return form", p, s.Value("reference"), http.MethodPost, Form(ret)), 200)
		}
		s.Outcomes("no outcome", 0)
	})...)
	out = append(out, per(providersFeature, "a browser return for an unknown reference is shown that the payment is being processed and nothing else happens", "005/US6", returns, func(s *Session, p Provider) {
		s.Use(p)
		s.Want(s.Return("the player's browser returns naming unknown-ref", p, "unknown-ref", http.MethodGet, nil), 200)
		s.Outcomes("no outcome", 0)
	})...)
	return out
}

func crypto(p Provider) bool { return p.Crypto }

// features/payments/crypto.feature, for Cozeni and Morphosis.
func cryptoScenarios() []Def {
	var out []Def
	out = append(out, per(cryptoFeature, "a crypto deposit is answered with a payment address, a coin amount, a coin currency and a deadline, and no redirect", "005/FR-003", crypto, func(s *Session, p Provider) {
		s.Use(p)
		r := s.Deposit(p)
		s.WantPresent(r, "paymentAddress", "cryptoAmount", "cryptoCurrency", "validUntil")
		if r.Str("redirectUrl") != "" {
			s.t.Errorf("%s: a crypto deposit answered a redirect: %s", s.rec.Scenario, string(r.Raw))
		}
	})...)
	out = append(out, per(cryptoFeature, "a confirmation at or above the quote captures the payment, with an overpayment recorded as a variance", "005/FR-005", crypto, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Webhook("the quote, exactly", p, s.Value("reference"), map[string]any{"outcome": "success"}), 200)
		s.DepositAs(p, "payment2", "reference2")
		s.Want(s.Webhook("above the quote", p, s.Value("reference2"), map[string]any{"outcome": "overpayment"}), 200)
		s.Outcomes("two captures, the second with an overpayment", 2)
	})...)
	out = append(out, per(cryptoFeature, "a confirmation below the quote that the provider has not valued fails the payment with an underpayment variance and credits nothing", "005/FR-005", crypto, func(s *Session, p Provider) {
		s.Use(p)
		s.Deposit(p)
		control := map[string]any{"outcome": "underpayment"}
		if p.Name == "cozeni" {
			control["convertAmount"] = "0" // Cozeni's unpriced underpayment; Morphosis never values one
		}
		s.Want(s.Webhook("below the quote, not valued", p, s.Value("reference"), control), 200)
		s.Outcomes("the failure", 1)
	})...)
	out = append(out, per(cryptoFeature, "a confirmation below the quote that the provider has valued captures the payment at that value, with an underpayment variance", "005/FR-005",
		func(p Provider) bool { return p.Name == "cozeni" }, func(s *Session, p Provider) {
			// Only Cozeni values what arrived (convertAmount); Morphosis reports coin only.
			s.Use(p)
			s.Deposit(p)
			s.Want(s.Webhook("below the quote, valued", p, s.Value("reference"), map[string]any{"outcome": "underpayment"}), 200)
			s.Outcomes("the capture with an underpayment", 1)
		})...)
	out = append(out, per(cryptoFeature, "a confirmation whose received amount is missing or unreadable is treated as short", "005/FR-005", crypto, func(s *Session, p Provider) {
		field, short := "receivedAmount", map[string]string{"resultCode": "03"}
		if p.Name == "morphosis" {
			field, short = "coin_amount", nil
		}
		s.Use(p)
		s.Deposit(p)
		s.Want(s.Forged("with no received amount", p, Forgery{Fields: short, Drop: []string{field}}), 200)
		s.DepositAs(p, "payment2", "reference2")
		unreadable := map[string]string{field: "not-a-number"}
		for k, v := range short {
			unreadable[k] = v
		}
		s.Want(s.Forged("with an unreadable received amount", p, Forgery{Reference: s.Value("reference2"), Fields: unreadable}), 200)
		s.Outcomes("two failures", 2)
	})...)
	out = append(out, per(cryptoFeature, "a webhook from a crypto provider that does not sign is admitted only on the secret in its address", "005/FR-010",
		func(p Provider) bool { return p.Name == "morphosis" }, func(s *Session, p Provider) {
			s.Use(p)
			s.Deposit(p)
			s.Want(s.Forged("to another session's address", p, Forgery{Unsigned: true}), 200)
			s.Outcomes("no outcome", 0)
			s.Want(s.Webhook("to the session's own address", p, s.Value("reference"), map[string]any{"outcome": "success"}), 200)
			s.Outcomes("the capture", 1)
		})...)
	return out
}

// payout is a payout initiated under `request` and seen by the provider.
func (s *Session) payout(p Provider, request string) Response {
	r := s.PayoutInitiate("initiate the withdrawal payment under "+request, p, request)
	s.ProviderCalls(p, 1)
	return r
}

// features/payments/withdrawal-payments.feature, for the four payout providers.
func payoutScenarios() []Def {
	var out []Def
	out = append(out, per(payoutsFeature, "a withdrawal payment initiated under a request id calls the provider once and answers the provider's reference", "005/FR-004", pays, func(s *Session, p Provider) {
		s.Use(p)
		s.Want(s.payout(p, "request"), 200)
	})...)
	out = append(out, per(payoutsFeature, "a withdrawal payment initiated again under the same request id calls no provider and returns the first answer", "005/FR-008", pays, func(s *Session, p Provider) {
		s.Use(p)
		s.Want(s.payout(p, "request"), 200)
		s.Want(s.PayoutInitiate("initiate it again under the same request id", p, "request"), 200)
		s.ProviderCalls(p, 0)
	})...)
	for _, outcome := range []string{"completed", "failed", "reversed"} {
		outcome := outcome
		control := map[string]string{"completed": "success", "failed": "failure", "reversed": "reversed"}[outcome]
		out = append(out, per(payoutsFeature, "a provider's webhook reporting the withdrawal's outcome tells the brand once with the provider's reference ("+outcome+")", "005/FR-014",
			func(p Provider) bool { return pays(p) && contains(p.Payout.Outcomes, outcome) }, func(s *Session, p Provider) {
				s.Use(p)
				s.Want(s.payout(p, "request"), 200)
				s.Want(s.Webhook(outcome, p, p.Payout.Reference(s, "request"), map[string]any{"outcome": control}), 200)
				s.Outcomes("the brand told, once", 1)
			})...)
	}
	uf := func(p Provider) bool { return p.Name == "unityfinance" }
	out = append(out, per(payoutsFeature, "a status read for a withdrawal payment with no webhook asks the provider and answers its state or that the provider has no record", "005/FR-009", uf, func(s *Session, p Provider) {
		s.Use(p)
		s.Want(s.payout(p, "request"), 200)
		s.Want(s.PayoutStatus("the status read while the provider holds it open", "request"), 200)
		s.Want(s.Webhook("completed", p, s.Value("request"), map[string]any{"outcome": "success"}), 200)
		s.Want(s.PayoutStatus("the status read once the provider completed it", "request"), 200)
		s.Control(p, map[string]any{"outcome": "no_record"})
		s.Want(s.payout(p, "request2"), 200)
		s.Want(s.PayoutStatus("the status read for one the provider kept no record of", "request2"), 200)
	})...)
	out = append(out, per(payoutsFeature, "a withdrawal payment the provider refuses as a duplicate reference is reported to the brand as already initiated", "005/FR-004", uf, func(s *Session, p Provider) {
		s.Use(p)
		s.Control(p, map[string]any{"outcome": "duplicate"})
		s.Want(s.payout(p, "request"), 200)
	})...)
	out = append(out, per(payoutsFeature, "a withdrawal payment refused for an empty credential is reported as a credential failure and never as no record", "005/FR-004", uf, func(s *Session, p Provider) {
		empty := map[string]string{}
		for k, v := range p.Properties {
			empty[k] = v
		}
		empty["withdrawalApiKey"] = ""
		s.UseWith(p, empty)
		s.Want(s.PayoutInitiate("initiate with an empty credential", p, "request"), 200)
		s.ProviderCalls(p, 0)
		s.Want(s.PayoutStatus("the status read with an empty credential", "request"), http.StatusBadGateway)
		// The provider's own refusal of a credential: its 401.
		s.Terminal(p.Properties)
		s.Control(p, map[string]any{"outcome": "unauthorised"})
		s.Want(s.payout(p, "request2"), 200)
		s.Want(s.PayoutStatus("the status read the provider refuses", "request2"), http.StatusBadGateway)
	})...)
	return out
}

func contains(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}

// The receiver's scripted answers.
var (
	ok        = Answer{Status: http.StatusOK}
	unavail   = Answer{Status: http.StatusServiceUnavailable}
	badReq    = Answer{Status: http.StatusBadRequest}
	nothing   = Answer{SilentMs: 12_000}  // past the original's 10 s per attempt (callbackTimeout)
	nevermore = Answer{SilentMs: 120_000} // past the original's 15 s budget (outcomeTotalBudget)
)

// features/payments/outcomes.feature, through Digitarium: its webhook delivers the outcome before it
// answers, and it is the provider whose marker the original writes where its re-drive reads it
// (research, *Findings* 6 names T365's and Morphosis's as not).
func outcomeScenarios() []Def {
	p := ProviderNamed("digitarium")
	capture := func(s *Session, reference string) {
		s.Webhook("success", p, s.Value(reference), map[string]any{"outcome": "success"})
	}
	def := func(name string, run func(s *Session)) Def {
		return Def{Feature: outcomesFeature, Name: name, Requirement: "005/FR-015", Run: run}
	}
	undelivered := func(s *Session) {
		s.Use(p)
		s.Deposit(p)
		s.Script(s.Value("payment"), unavail)
		capture(s, "reference")
		s.Outcomes("four attempts, every one answered 503", 4)
	}
	return []Def{
		def("an outcome delivered to a receiver answering a server error or not at all is retried with backoff until it answers", func(s *Session) {
			s.Use(p)
			s.Deposit(p)
			s.Script(s.Value("payment"), unavail, unavail, ok)
			capture(s, "reference")
			s.Outcomes("503, 503, then delivered", 3)
			s.DepositAs(p, "payment2", "reference2")
			s.Script(s.Value("payment2"), nothing, ok)
			capture(s, "reference2")
			s.Outcomes("no answer, then delivered", 5)
		}),
		def("an outcome a receiver refuses as a wrong request is not retried and the provider session shows the refusal", func(s *Session) {
			s.Use(p)
			s.Deposit(p)
			s.Script(s.Value("payment"), badReq)
			capture(s, "reference")
			s.Outcomes("one attempt, refused", 1)
		}),
		{Feature: outcomesFeature, Name: "an outcome whose retries are exhausted stays on the provider session as undelivered and is listable", Requirement: "005/FR-016", Run: func(s *Session) {
			s.Use(p)
			s.Deposit(p)
			s.Script(s.Value("payment"), nevermore)
			capture(s, "reference")
			s.Outcomes("no answer within the budget", 2)
			// The second attempt is seen when it arrives; the original writes the undelivered marker only
			// when it times out, and a re-drive before then finds nothing to re-drive (run 1, 2026-10-20).
			time.Sleep(s.cfg.Attempt)
			s.Script(s.Value("payment"), unavail)
			s.Want(s.Redrive("the re-drive runs while the receiver still fails"), 200)
			s.Outcomes("re-driven and still undelivered", 6)
		}},
		{Feature: outcomesFeature, Name: "an undelivered outcome is delivered once when an operator re-drives it or the receiver recovers (an operator re-drives it)", Requirement: "005/FR-016", Run: func(s *Session) {
			undelivered(s)
			s.Script(s.Value("payment"), ok)
			s.Want(s.Redrive("an operator re-drives it"), 200)
			s.Outcomes("delivered once", 5)
		}},
		{Feature: outcomesFeature, Name: "an undelivered outcome is delivered once when an operator re-drives it or the receiver recovers (the receiver recovers and the next re-drive runs)", Requirement: "005/FR-016", Run: func(s *Session) {
			undelivered(s)
			s.Script(s.Value("payment"), ok)
			s.Want(s.Redrive("the next re-drive runs"), 200)
			s.Outcomes("delivered once", 5)
		}},
		{Feature: outcomesFeature, Name: "a refused outcome is left by the automatic re-drive and delivered when an operator re-drives it", Requirement: "005/FR-016", Run: func(s *Session) {
			s.Use(p)
			s.Deposit(p)
			s.Script(s.Value("payment"), badReq)
			capture(s, "reference")
			s.Outcomes("refused", 1)
			s.Want(s.Redrive("the next re-drive runs"), 200)
			s.Outcomes("left by the re-drive", 1)
			s.Script(s.Value("payment"), ok)
			s.Want(s.Redrive("an operator re-drives it"), 200)
			s.Outcomes("delivered once", 2)
		}},
	}
}
