Feature: The gateway sends a player to a payment provider and the deposit comes back captured
  The cashier asks the gateway to initiate a hosted page deposit for a sub-brand, an amount, a
  currency and a method. The gateway reads the sub-brand's credential for the provider at that
  moment, calls the provider, records a provider session under a reference it minted, and answers
  with where to send the player. The provider posts a webhook; the gateway verifies it, maps the
  provider's status to captured or failed, and tells the cashier once. The player's browser return
  shows a page and settles nothing.

  Scenario: a deposit initiated for a sub-brand with a credential answers the provider's reference and where to send the player, and records a provider session
    Given a sub-brand with a credential for a payment provider
    When the cashier initiates a deposit naming that provider, an amount in minor units and a currency
    Then the gateway answers with the provider's reference and where to send the player
    And a provider session exists under that reference holding the sub-brand, where to deliver the outcome, and the terminal

  Scenario: a deposit initiated for a sub-brand with no credential for the provider is refused naming both, and no provider is called
    Given a sub-brand with no credential for the payment provider named
    When the cashier initiates a deposit naming that provider
    Then it is refused naming the provider and the sub-brand
    And no payment provider is called

  Scenario: a verified webhook reporting success tells the cashier the payment is captured, once, and the provider session records it delivered
    Given an initiated provider session
    When the payment provider's webhook reports success and verifies
    Then the cashier is told the payment is captured, once
    And the provider session records the outcome delivered

  Scenario: a webhook reporting a failure tells the cashier the payment failed with the provider's code and a reason a player can read
    Given an initiated provider session
    When the payment provider's webhook reports a failure
    Then the cashier is told the payment failed with the provider's code and a reason a player can read

  Scenario: a webhook whose signature does not verify is acknowledged, logged, and moves nothing
    Given an initiated provider session
    When a webhook arrives whose signature does not verify
    Then it is acknowledged to the payment provider
    And it is logged
    And nothing moves

  Scenario: a webhook for a reference the gateway does not know is acknowledged and moves nothing
    Given no provider session under reference "unknown-ref"
    When a webhook arrives for reference "unknown-ref"
    Then it is acknowledged
    And nothing moves

  Scenario: the same webhook arriving again for a captured provider session is acknowledged and the cashier is not told twice
    Given a provider session already captured
    When the same webhook arrives again
    Then it is acknowledged
    And the cashier is not told twice

  Scenario: a webhook reporting a status that is not final leaves the provider session open and the cashier untold
    Given an initiated provider session
    When the payment provider's webhook reports a status that is not final
    Then the provider session stays open
    And the cashier is not told

  Scenario: a deposit is initiated with the credential read from the secret store at that moment
    Given a credential saved for a sub-brand and a payment provider
    When a deposit is initiated for them
    Then the payment provider is called with that credential, read from the secret store at that moment

  Scenario: a rotated credential is used by the next deposit with no restart
    Given a credential rotated for a sub-brand and a payment provider
    When the next deposit is initiated for them
    Then the payment provider is called with the new values
    And no restart happened

  Scenario: a provider's private signing key is read and parsed at request time and never cached across rotations
    Given a payment provider whose requests are signed with a private signing key
    When a request to it is signed
    Then the signing key is read and parsed at request time, never cached across rotations

  Scenario: no credential value and no personal data of a player appears in what is logged or traced
    Given any request to a payment provider or any outcome from one
    When it is logged or traced
    Then no credential value appears
    And no personal data of a player appears

  Scenario: a player's browser returning from the payment provider is sent on to the cashier's return page and settles nothing
    Given a provider session
    When the player's browser returns from the payment provider
    Then the player is sent on to the cashier's return page
    And no outcome is delivered

  Scenario: a browser return for an unknown reference is sent to the cashier's return page and nothing else happens
    Given no provider session under reference "unknown-ref"
    When a player's browser returns from the payment provider naming reference "unknown-ref"
    Then the player is sent to the cashier's return page
    And nothing else happens
