Feature: A sub-brand's deposit bounds per method
  A payments operator sets the least and the most a deposit by one method may be, in one currency.
  A deposit outside them is refused before any payment provider is asked.

  Scenario: a payments operator sets a method's deposit bounds and they are read back
    Given a sub-brand with no deposit bounds
    When a payments operator sets the deposit bounds for the method "card" in "EUR" to at least "10.00" and at most "5000.00"
    Then the sub-brand's deposit bounds for the method "card" in "EUR" are "10.00" and "5000.00"

  Scenario: a deposit below the method's deposit bounds is refused before a provider is asked
    Given a sub-brand whose deposit bounds for the method "card" in "EUR" are "10.00" and "5000.00"
    When a player starts a deposit of "5.00 EUR" by the method "card"
    Then the deposit is refused as below the minimum, naming "10.00 EUR"
    And no payment provider is called

  Scenario: a deposit above the method's deposit bounds is refused before a provider is asked
    Given a sub-brand whose deposit bounds for the method "card" in "EUR" are "10.00" and "5000.00"
    When a player starts a deposit of "6000.00 EUR" by the method "card"
    Then the deposit is refused as above the maximum, naming "5000.00 EUR"
    And no payment provider is called

  Scenario: a method with no deposit bounds takes any amount its terminal allows
    Given a sub-brand with no deposit bounds
    When a player starts a deposit of "5.00 EUR" by the method "card"
    Then the deposit is initiated

  Scenario: a test payment answers the provider's outcome and credits no wallet
    Given a sub-brand with a credential for a payment provider that has a test mode
    When a payments operator makes a test payment with that credential
    Then the provider's outcome is answered
    And no wallet is credited and no deposit is recorded

  Scenario: a brand's operator reads the deposits of the sub-brand naming their brand, and no other's
    Given a sub-brand naming the brand "goldvibe" and another naming no brand
    When an operator of the brand "goldvibe" reads deposits
    Then the deposits of the sub-brand naming "goldvibe" are listed
    And a read of the other sub-brand's deposits is refused

  Scenario: a deposit is found by its payment reference
    Given a captured deposit with a payment reference
    When an operator reads deposits by that payment reference
    Then only that deposit is listed
