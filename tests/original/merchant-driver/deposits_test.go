//go:build e2e

package merchantdriver

import (
	"net/http"
	"testing"
)

// features/payments/deposits.feature, scenario for scenario, against the original's cashier with its
// simulator credential. Each scenario seeds its own brand. A scenario the original answers otherwise
// than its feature expects fails in the run's result and is still recorded: the recording is the
// original's behaviour, and the failure is a finding for the spec.

func TestDeposits(t *testing.T) {
	sc(t, "a capture reported by the gateway captures the payment, confirms the deposit to the wallet once and is acknowledged only after the wallet answers", "004/FR-021", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		s.Want(s.Capture("capture", s.CaptureBody(nil), nil), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "the same capture reported again leaves the payment captured, calls the wallet nothing and is acknowledged", "004/FR-019", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		body := s.CaptureBody(nil)
		s.Want(s.Capture("capture", body, nil), 200)
		s.Want(s.Capture("capture again", body, nil), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a capture for a sub-brand of type payments that names no brand is captured with no wallet called", "004/FR-022", func(s *Session) {
		s.SubBrand()
		s.Deposit(false)
		s.Want(s.Capture("capture", s.CaptureBody(nil), nil), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a failure reported by the gateway fails the payment with the provider's code and reason and reaches the brand", "004/FR-025", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		s.Want(s.Fail(map[string]any{"failureCode": "05", "failureReason": "Do not honour"}), 200)
		s.Want(s.Read(), 200)
		// and a failure that names neither takes the default reason
		s.ids["payment"] = ""
		s.Deposit(true)
		s.Want(s.Fail(map[string]any{}), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a payment past its deadline whose provider session is resolved or unknown expires with its reason and reaches the brand", "004/FR-020", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		s.Listed()
		s.Want(s.Sweep(), 200)
		s.WantField(s.Want(s.Read(), 200), "status", "EXPIRED")
	})
	sc(t, "a payment past its deadline whose outcome the gateway still holds undelivered is not expired and waits again", "004/FR-020", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		s.Undelivered()
		s.Listed()
		s.Want(s.Sweep(), 200)
		s.WantField(s.Want(s.Read(), 200), "status", "AWAITING_REDIRECT")
	})
	sc(t, "a player abandoning the session with its cashier ticket fails the payment as abandoned and reaches the brand", "004/FR-024", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		s.Want(s.Abandon(s.ID("player")), 200)
		s.Want(s.Read(), 200)
		// another player's abandon is refused: the original proves ownership by the player id
		s.ids["payment"] = ""
		s.Deposit(true)
		s.Want(s.Abandon(s.ID("otherPlayer")), 404)
	})
	sc(t, "a failure, an expiry or an abandon arriving for a captured payment changes nothing and is acknowledged", "004/FR-019", func(s *Session) {
		s.SubBrand()
		for _, arrival := range []string{"a failure", "an expiry", "an abandon"} {
			s.ids["payment"] = ""
			s.Deposit(true)
			s.Want(s.Capture("capture", s.CaptureBody(nil), nil), 200)
			switch arrival {
			case "a failure":
				s.Want(s.Fail(map[string]any{"failureCode": "late"}), 200)
			case "an expiry":
				s.Want(s.Sweep(), 200)
			case "an abandon":
				s.Want(s.Abandon(s.ID("player")), 200)
			}
			s.Want(s.Read(), 200)
		}
	})
	sc(t, "a payment past its deadline that nothing has yet touched reads as expired with its reason", "004/FR-017", func(s *Session) {
		// The original's read computes the lazy expiry from `expires_at`; with its 30-minute deadline
		// a run cannot wait, so the read is recorded at once and the replay sets the kit's clock.
		s.SubBrand()
		s.Deposit(true)
		s.Want(s.Read(), 200)
	})
	sc(t, "a crypto underpayment credits the valuation and keeps the invoiced amount on record", "004/FR-026", func(s *Session) {
		s.SubBrand()
		s.Want(s.Open(10000, "EUR", s.Customer(true)), 200)
		s.Want(s.Submit("SIM"), 200)
		s.Want(s.Capture("capture", s.CaptureBody(map[string]string{"variance": "UNDERPAYMENT", "receivedCryptoAmount": "0.0012",
			"receivedConvertAmount": "80.00", "receivedConvertCurrency": "EUR"}), nil), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a crypto capture whose valuation cannot be trusted is refused and left undelivered at the gateway", "004/FR-026", func(s *Session) {
		s.SubBrand()
		for _, v := range []map[string]string{
			{"receivedConvertAmount": "0", "receivedConvertCurrency": "EUR"},
			{"receivedConvertAmount": "150.00", "receivedConvertCurrency": "EUR"},
			{"receivedConvertAmount": "80.00"},
			{"receivedConvertAmount": "80.00", "receivedConvertCurrency": "USD"},
		} {
			s.ids["payment"] = ""
			s.Want(s.Open(10000, "EUR", s.Customer(true)), 200)
			s.Want(s.Submit("SIM"), 200)
			v["variance"] = "UNDERPAYMENT"
			v["receivedCryptoAmount"] = "0.0012"
			r := s.Capture("capture", s.CaptureBody(v), nil)
			if r.Status/100 == 2 {
				s.t.Errorf("an untrustworthy valuation %v was acknowledged: %s", v, string(r.Raw))
			}
			s.Want(s.Read(), 200)
		}
	})
	sc(t, "a crypto overpayment credits the invoiced amount and records the surplus", "004/FR-026", func(s *Session) {
		s.SubBrand()
		s.Want(s.Open(10000, "EUR", s.Customer(true)), 200)
		s.Want(s.Submit("SIM"), 200)
		s.Want(s.Capture("capture", s.CaptureBody(map[string]string{"variance": "OVERPAYMENT", "receivedCryptoAmount": "0.0020",
			"receivedConvertAmount": "130.00", "receivedConvertCurrency": "EUR"}), nil), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a captured underpayment delivered again keeps the invoiced amount on record", "004/FR-026", func(s *Session) {
		s.SubBrand()
		s.Want(s.Open(10000, "EUR", s.Customer(true)), 200)
		s.Want(s.Submit("SIM"), 200)
		body := s.CaptureBody(map[string]string{"variance": "UNDERPAYMENT", "receivedCryptoAmount": "0.0012",
			"receivedConvertAmount": "80.00", "receivedConvertCurrency": "EUR"})
		s.Want(s.Capture("capture", body, nil), 200)
		s.Want(s.Capture("capture again", body, nil), 200)
		s.Want(s.Read(), 200)
	})
}

func TestSignatures(t *testing.T) {
	const secret = "whsec-driver-test"
	sc(t, "a capture signed under the payment provider's webhook secret and stamped within 300 seconds is accepted", "004/FR-018", func(s *Session) {
		s.SubBrand()
		s.WebhookSecret(secret)
		s.Deposit(true)
		body := s.CaptureBody(nil)
		s.Want(s.Capture("capture", body, s.Signed(secret, body, 0)), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a capture from a payment provider with no webhook secret is accepted unsigned", "004/FR-018", func(s *Session) {
		s.SubBrand()
		s.Deposit(true)
		s.Want(s.Capture("capture", s.CaptureBody(nil), nil), 200)
		s.Want(s.Read(), 200)
	})
	sc(t, "a capture carrying one of the signature and the stamped time without the other is refused as missing its signature", "004/FR-018", func(s *Session) {
		s.SubBrand()
		s.WebhookSecret(secret)
		s.Deposit(true)
		body := s.CaptureBody(nil)
		signed := s.Signed(secret, body, 0)
		s.Want(s.Capture("capture with the signature only", body, map[string]string{"X-PSP-Signature": signed["X-PSP-Signature"]}), http.StatusUnauthorized)
		s.Want(s.Capture("capture with the stamped time only", body, map[string]string{"X-PSP-Timestamp": signed["X-PSP-Timestamp"]}), http.StatusUnauthorized)
		s.Want(s.Read(), 200)
	})
	sc(t, "a capture stamped with a time older than 300 seconds or whose signature does not match is refused and moves nothing", "004/FR-018", func(s *Session) {
		s.SubBrand()
		s.WebhookSecret(secret)
		s.Deposit(true)
		body := s.CaptureBody(nil)
		s.Want(s.Capture("capture stamped too long ago", body, s.Signed(secret, body, 301)), http.StatusForbidden)
		s.Want(s.Capture("capture signed under another secret", body, s.Signed("another-secret", body, 0)), http.StatusForbidden)
		other := s.CaptureBody(map[string]string{"extra": "x"})
		s.Want(s.Capture("capture signed over another body", body, s.Signed(secret, other, 0)), http.StatusForbidden)
		s.Want(s.Read(), 200)
	})
	// "a signed capture arriving while the secret cannot be read …" needs the original's substrate
	// down mid-request; it is not driven. The unit case CashierCaptureAuthSpec "503, not 401" is
	// ported instead (tests/original/README.md, *Recording the cashier directly*).
}

func TestCashier(t *testing.T) {
	sc(t, "submitting a session already submitted returns the first submit's answer and makes no second payment", "004/FR-015", func(s *Session) {
		s.SubBrand()
		s.Want(s.Open(5000, "EUR", s.Customer(true)), 200)
		s.Want(s.Submit("SIM"), 200)
		s.Want(s.Submit("SIM"), 200) // the original has no guard: a second payment (spec 004 correction 2)
	})
}
