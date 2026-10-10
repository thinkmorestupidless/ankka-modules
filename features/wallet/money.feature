Feature: Money is exact and a repeated command does nothing twice
  Every amount is a whole number of the currency's minor units. Every money command carries a key
  the caller chose, and sending it again returns the first answer and moves nothing.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: an amount is a whole number of minor units
    Given the wallet has a real balance of "10000"
    When the player places a bet of "1050" in round "r1"
    Then the real balance is "8950"
    And the bet's amount is recorded as the whole number "1050"

  Scenario: a money command sent again under its key returns the first answer and moves nothing
    Given a money command was sent under key "tx-1"
    When the same money command is sent again under key "tx-1"
    Then the first answer is returned
    And the real balance is unchanged

  Scenario: an amount that is not a whole number of minor units is refused naming the amount
    When a money command carries the amount "10.50"
    Then it is refused, naming the amount

  Scenario: a money command without a key is refused
    When a money command is sent with no key
    Then it is refused

  Scenario: a seen key carried by a different money command is refused naming the first
    Given a bet of "1000" was placed under key "tx-1"
    When a bet of "2000" is sent under key "tx-1"
    Then it is refused as "key reused", naming "tx-1" and the first bet of "1000"
    And no transaction is written
