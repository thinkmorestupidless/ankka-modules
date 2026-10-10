Feature: A captured deposit reaches the brand's wallet once, and a failed, expired or abandoned one resolves
  The payment provider's outcome reaches the cashier through the gateway. The cashier hands a
  captured deposit to the brand's wallet once, keyed by the payment id, and acknowledges the capture
  only after the wallet has answered; it keeps trying while the wallet is unreachable, never credits
  twice, and makes a deposit it cannot credit visible rather than silent. A player who walks away,
  or whose payment the provider declines, sees a resolved status with a reason.

  Background:
    Given a sub-brand "acme" naming brand "goldvibe"

  Scenario: a capture reported by the gateway captures the payment, confirms the deposit to the wallet once and is acknowledged only after the wallet answers
    Given a payment awaiting redirect
    When the gateway reports the payment provider's capture
    Then the payment is captured
    And the brand's wallet is asked to confirm the deposit once, keyed by the payment id, with the payment provider's reference as the payment reference
    And the capture is acknowledged to the gateway only after the wallet has answered
    And a "payment.captured" outcome reaches the brand for the payment

  Scenario: the same capture reported again leaves the payment captured, calls the wallet nothing and is acknowledged
    Given a captured payment
    When the gateway reports the same capture again
    Then the payment stays captured
    And the wallet is not called again
    And the player's deposit count rises by nothing
    And the gateway is acknowledged

  Scenario: a capture for a sub-brand of type payments that names no brand is captured with no wallet called
    Given a sub-brand of type "Payments" that names no brand
    When the gateway reports the capture of its payment
    Then the payment is captured
    And no wallet is called
    And the capture is acknowledged

  Scenario: a failure reported by the gateway fails the payment with the provider's code and reason and reaches the brand
    Given a payment awaiting redirect
    When the gateway reports a failure with code "declined" and reason "Payment declined"
    Then the payment is failed with that code and reason
    And the gateway is acknowledged
    And a "payment.failed" outcome reaches the brand carrying both

  Scenario: a payment past its deadline whose provider session is resolved or unknown expires with its reason and reaches the brand
    Given a payment awaiting redirect whose deadline has passed and whose provider session the gateway reports as resolved or unknown
    When the deadline fires
    Then the payment is expired with the reason "Payment not completed"
    And a "payment.expired" outcome reaches the brand

  Scenario: a payment past its deadline whose outcome the gateway still holds undelivered is not expired and waits again
    Given a payment awaiting redirect whose deadline has passed and whose outcome the gateway still holds undelivered
    When the deadline fires
    Then the payment is not expired
    And the cashier waits again
    And the payment's status says why

  Scenario: a player abandoning the session with its cashier ticket fails the payment as abandoned and reaches the brand
    Given a payment awaiting redirect
    When the player's frontend abandons the session with its cashier ticket
    Then the payment is failed with code "player_abandoned" and reason "Payment not completed"
    And a "payment.abandoned" outcome reaches the brand

  Scenario Outline: a failure, an expiry or an abandon arriving for a captured payment changes nothing and is acknowledged
    Given a captured payment
    When <arrival> arrives for it
    Then nothing changes
    And the caller is acknowledged

    Examples:
      | arrival    |
      | a failure  |
      | an expiry  |
      | an abandon |

  Scenario: a payment past its deadline that nothing has yet touched reads as expired with its reason
    Given a payment past its deadline that nothing has yet touched
    When the player's frontend reads it
    Then it is told "EXPIRED" with the reason "Payment not completed"

  Scenario: a payment captured while the wallet is unreachable is credited once when the wallet comes back, and acknowledged only then
    Given a payment captured while the brand's wallet is unreachable
    When the wallet comes back
    Then the deposit is credited once
    And the capture was not acknowledged to the gateway until it was

  Scenario: a capture whose wallet answer was lost in flight is confirmed again under the same payment id and credited once
    Given a payment captured whose wallet answer was lost in flight
    When the cashier asks the wallet again under the same payment id
    Then the wallet returns the first outcome
    And the balance has risen once

  Scenario: a capture the wallet refuses for a wallet the player does not hold stays captured, stops retrying and is visible for an operator to retry
    Given a payment the wallet refuses because the player holds no wallet in its currency
    When the refusal arrives
    Then the payment is captured
    And the cashier records the refusal and stops retrying
    And the payment is listed among the captured deposits not credited, with the wallet's reason
    And an operator can retry it once the cause is fixed

  Scenario: a payment captured and unacknowledged for longer than the installation's bound is counted on the shared payments' status
    Given the captured deposits not credited
    When a payment has been captured and unacknowledged for longer than the installation's bound
    Then the shared payments' status counts it

  Scenario: a crypto underpayment credits the valuation and keeps the invoiced amount on record
    Given a payment of "10000" minor units of "EUR" awaiting crypto
    When the gateway reports an underpayment valued at "80.00" "EUR"
    Then the wallet is credited "8000"
    And the payment records the variance, the amount credited and the invoiced "10000"
    And the "payment.captured" outcome carries "8000"

  Scenario Outline: a crypto capture whose valuation cannot be trusted is refused and left undelivered at the gateway
    Given a payment of "10000" minor units of "EUR" awaiting crypto
    When the gateway reports a capture whose valuation <fault>
    Then the capture is refused
    And nothing is credited
    And the gateway is not acknowledged, so that it keeps the outcome undelivered

    Examples:
      | fault                     |
      | is "0"                    |
      | exceeds "100.00"          |
      | carries no currency       |
      | carries another currency  |

  Scenario: a crypto overpayment credits the invoiced amount and records the surplus
    Given a payment awaiting crypto
    When the gateway reports an overpayment
    Then the invoiced amount is credited
    And the surplus is recorded on the payment

  Scenario: a captured underpayment delivered again keeps the invoiced amount on record
    Given a captured underpayment
    When its capture is delivered again
    Then the invoiced amount on record is not overwritten
