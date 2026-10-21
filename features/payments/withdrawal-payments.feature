Feature: A brand's withdrawal is paid out through a payment provider and its outcome delivered
  A brand approves a withdrawal and asks the gateway to pay it through a provider: by bank account,
  by transfer or to a crypto address. The gateway initiates the withdrawal payment under the
  withdrawal request's id, records a provider session, and answers with the provider's reference.
  The outcome arrives by webhook or is fetched by a status read; either way the gateway tells the
  brand once.

  Scenario: a withdrawal payment initiated under a request id calls the provider once and answers the provider's reference
    Given a brand that allows the gateway to pay its withdrawals
    When the brand initiates a withdrawal payment with request id "w1", an amount, a currency and the method's details
    Then the payment provider is called once under "w1"
    And the answer carries the provider's reference

  Scenario: a withdrawal payment initiated again under the same request id calls no provider and returns the first answer
    Given a withdrawal payment initiated under request id "w1"
    When the brand initiates it under "w1" a second time
    Then the payment provider is not called again
    And the first answer is returned

  Scenario Outline: a provider's webhook reporting the withdrawal's outcome tells the brand once with the provider's reference
    Given a withdrawal payment initiated under request id "w1"
    When the payment provider's webhook reports it <outcome>
    Then the brand is told the withdrawal payment is <outcome>, with the provider's reference, once

    Examples:
      | outcome   |
      | completed |
      | failed    |
      | reversed  |

  Scenario: a status read for a withdrawal payment with no webhook asks the provider and answers its state or that the provider has no record
    Given a withdrawal payment initiated under request id "w1" and no webhook for it
    When the brand asks for its status
    Then the gateway asks the payment provider
    And it answers transient, completed, failed, reversed, or that the provider has no record of it

  Scenario: a withdrawal payment the provider refuses as a duplicate reference is reported to the brand as already initiated
    Given a payment provider that refuses a withdrawal payment as a duplicate reference
    When the brand initiates it
    Then the brand is told it was already initiated, the provider holding it

  Scenario: a withdrawal payment refused for an empty credential is reported as a credential failure and never as no record
    Given a withdrawal payment initiated with an empty credential
    When the payment provider refuses it
    Then the refusal is reported as a credential failure
    And never as the provider having no record of it
