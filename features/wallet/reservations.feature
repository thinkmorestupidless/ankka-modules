Feature: Money is reserved against an order and released
  A reservation sets money aside from real balance under an order's key. What is available to a
  bet or a hold is real balance less what is reserved. A release returns part or all of a
  reservation. Restricted funds are never reserved.

  Background:
    Given a player with a wallet in "EUR"
    And a real balance of "1000", no restricted funds and no deposit turnover pending

  Scenario: a reservation sets money aside from real balance and reduces what is available
    When a reservation of "600" is placed for order "o1"
    Then the real balance is "1000"
    And the reserved amount is "600"
    And the available amount is "400"
    And a reservation transaction of "600" is written

  Scenario: a reservation above the available amount is refused as insufficient funds
    Given a reservation of "600" is placed for order "o1"
    When a reservation of "500" is placed for order "o2"
    Then it is refused as "insufficient funds"
    And the reserved amount is "600"

  Scenario: a release returns part or all of a reservation to the available amount
    Given a reservation of "600" is placed for order "o1"
    When a release of "200" is placed for order "o1"
    Then the reserved amount is "400"
    And the available amount is "600"
    When a release of "400" is placed for order "o1"
    Then the reserved amount is "0"
    And the available amount is "1000"

  Scenario: a reservation repeated under its key returns the first outcome and moves nothing
    Given a reservation of "600" is placed for order "o1"
    When a reservation of "600" is placed for order "o1" again
    Then the outcome is the first outcome
    And the reserved amount is "600"

  Scenario: restricted funds are never reserved
    Given a real balance of "100" and restricted funds of "900"
    When a reservation of "600" is placed for order "o1"
    Then it is refused as "insufficient funds"

  Scenario: a hold or a bet draws only on real balance that is not reserved
    Given a reservation of "600" is placed for order "o1"
    When a hold of "500" is placed for request "w1"
    Then it is refused as "insufficient funds"
    When a hold of "400" is placed for request "w1"
    Then the real balance is "600"
    And the reserved amount is "600"
