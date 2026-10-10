Feature: A migrated player's money and rewards are exactly what they were
  A Spinvibe player who signed out on Sunday evening signs in on Monday morning on the rebuild.
  Their real balance, their restricted funds and its release progress, their open withdrawal,
  their deposit history and their favourites are what they were. They notice nothing but a
  maintenance page overnight.

  Background:
    Given the migration of brand "spinvibe" from the original

  Scenario: a player's real balance and restricted funds record migrate with the same balance, requirement and progress, linked to a reward in the same state
    Given a player with a real balance of "12345" and an active restricted funds record of "2500" with release progress of "4000" of a wagering requirement of "10000"
    When the migration has run
    Then the player's wallet answers the same real balance
    And a restricted funds record with the same balance, wagering requirement and release progress
    And the record is linked to a reward in the same state

  Scenario: a player's wallets in two currencies migrate each with its own balances and records, neither naming the other
    Given a player with wallets in "EUR" and "USD"
    When the migration has run
    Then both wallets exist with their own balances and records
    And neither names the other

  Scenario: a converted record awaiting its cap migrates with the same allowance and deposit counters and the reward's cap clock
    Given a player whose restricted funds are converted and awaiting their cap
    When the migration has run
    Then the record is "CONVERTED_PENDING_CAP" with the same remaining allowance and deposits since conversion
    And the reward carries the same cap clock

  Scenario: the parity report has every player's row OK or explained with evidence, and none unexplained
    Given every player of the brand
    When the parity report is produced
    Then every row is "OK", or an explained class with the evidence named
    And no row is "UNEXPLAINED"

  Scenario: a player's past deposit is listed in their transaction history with its date, amount and reference as before
    Given a player who deposited "5000" last month
    When they open their transaction history after the migration
    Then the deposit is listed with its date, amount and payment reference as before
