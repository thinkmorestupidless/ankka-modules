Feature: Non-player accounts hold money that belongs to no player
  A non-player account is a ledger in the wallet service owned by a service principal: a pool
  account for a market, which never goes below zero, or a clearing account for a pool, which may
  go below zero to its credit line. A payout credits a player from one; an operator adjusts one
  with a reason and a signature.

  Background:
    Given a player "ada" with a wallet in "EUR" and a real balance of "1000"

  Scenario: a non-player account is a ledger owned by a service principal and keyed by what it is for
    When the service "exchange" moves "100" from the wallet of "ada" into the pool account of market "m1" in "EUR"
    Then the pool account of market "m1" in "EUR" exists, owned by the service "exchange"
    And its balance is "100"
    And the real balance of "ada" is "900"

  Scenario: a resolution payment credits a player's real balance from a non-player account keyed by the market and the position
    Given the pool account of market "m1" in "EUR" holds "1000"
    When a resolution payment of "300" is paid to "ada" from the pool account of market "m1" for position "p1"
    Then the real balance of "ada" is "1300"
    And the pool account of market "m1" in "EUR" holds "700"
    And a resolution payment transaction of "300" is written

  Scenario: a pool account is refused a move that would take it below zero
    Given the pool account of market "m1" in "EUR" holds "200"
    When a resolution payment of "300" is paid to "ada" from the pool account of market "m1" for position "p1"
    Then it is refused as "insufficient funds"
    And the pool account of market "m1" in "EUR" holds "200"

  Scenario: a clearing account may go below zero to its credit line and no further
    Given the clearing account of pool "north" in "EUR" holds "0" with a credit line of "500"
    When the service "clearing" moves "500" from the clearing account of pool "north" to the wallet of "ada"
    Then the clearing account of pool "north" in "EUR" holds "-500"
    When the service "clearing" moves "1" from the clearing account of pool "north" to the wallet of "ada"
    Then it is refused as "credit line reached"

  Scenario: an operator adjusts a non-player account with a reason and a signature
    Given the clearing account of pool "north" in "EUR" holds "-500" with a credit line of "500"
    When an operator adjusts the clearing account of pool "north" by "500" with the reason "net settled" and a signature
    Then the clearing account of pool "north" in "EUR" holds "0"
    And an adjustment transaction of "500" with the reason "net settled" and the actor is written
