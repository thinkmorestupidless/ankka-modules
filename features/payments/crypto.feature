Feature: A crypto deposit is quoted, paid and checked for short payment
  A player chooses a crypto method. The gateway asks the payment provider for a quote, answers with
  a payment address, a coin amount, a coin currency and a deadline, and the player sends coin. When
  the provider confirms what arrived, the gateway captures only when the amount received is not
  short of the quote; a short payment is reported to the cashier as a failure carrying the variance.

  Scenario: a crypto deposit is answered with a payment address, a coin amount, a coin currency and a deadline, and no redirect
    Given a sub-brand with a credential for a crypto payment provider
    When the cashier initiates a deposit by a crypto method
    Then the answer carries a payment address, a coin amount, a coin currency and a deadline
    And no redirect

  Scenario: a confirmation at or above the quote captures the payment, with an overpayment recorded as a variance
    Given a quoted crypto deposit
    When the payment provider confirms a coin amount equal to or above the quote
    Then the cashier is told the payment is captured
    And an overpayment is recorded as a variance on the outcome

  Scenario: a confirmation below the quote fails the payment with an underpayment variance and credits nothing
    Given a quoted crypto deposit
    When the payment provider confirms a coin amount below the quote
    Then the cashier is told the payment failed with an underpayment variance and the amounts quoted and received
    And nothing is credited

  Scenario: a confirmation whose received amount is missing or unreadable is treated as short
    Given a quoted crypto deposit
    When the payment provider confirms with a received amount that is missing or unreadable
    Then it is treated as short

  Scenario: a webhook from a crypto provider that does not sign is admitted only on the secret in its address
    Given a crypto payment provider that does not sign its webhooks
    When its webhook arrives
    Then it is admitted only on the secret for that provider session carried in its address
