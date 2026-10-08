Feature: A player's bet is funded and settled in one step
  The game provider places a bet for a round, then credits a win, rolls the bet back, or settles the
  round as a loss. The wallet funds the stake from real balance and open restricted funds, records
  which source funded which leg, and returns the balances. A later settlement of the round reads
  the bet's legs back, so a win follows its source and a rollback restores each leg to where it
  came from.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: a bet funded from real balance alone opens its round with one real leg
    Given the wallet has a real balance of "10000" and no restricted funds
    When the player places a bet of "1000" in round "r1"
    Then the real balance is "9000"
    And the bet records a real leg of "1000" and no restricted funds legs
    And the bet's wallet type is "real"
    And round "r1" is open

  Scenario: a win settles the bet and closes the round
    Given round "r1" is open with a bet of "1000"
    When a win of "2500" is credited for round "r1"
    Then the real balance rises by "2500"
    And the bet is settled
    And round "r1" is no longer open
    And the win's wallet type mirrors the bet's

  Scenario: a rollback restores the stake, voids the bet and closes the round
    Given round "r1" is open with a bet of "1000"
    When the bet in round "r1" is rolled back
    Then the real balance rises by "1000"
    And the bet is voided
    And round "r1" is no longer open
    And deposit turnover pending is not restored

  Scenario: settling a round as a loss moves no money and closes the round
    Given round "r1" is open
    When round "r1" is settled as a loss
    Then no balance changes
    And the bet is marked as a loss
    And round "r1" is no longer open

  Scenario: a bet the wallet cannot fund is refused and opens no round
    Given the wallet has a real balance of "500"
    When the player places a bet of "1000" in round "r1"
    Then the bet is refused as "insufficient funds"
    And no transaction is written
    And no round opens

  Scenario: a correction debit that allows a negative balance is funded from real balance with no contribution
    Given the wallet has a real balance of "0"
    When a correction debit of "1000" is placed allowing a negative balance
    Then the real balance is "-1000"
    And the debit is funded entirely from real balance
    And no contribution is recorded

  Scenario: a large win on a large stake is credited exactly
    Given the wallet has a real balance of "50000"
    When the player places a bet of "50000" in round "r7"
    And a win of "500000" is credited for round "r7"
    Then the real balance is "500000"

  Scenario: a win credited twice under the same key returns the first balances and writes nothing
    Given round "r1" is open with a bet of "1000"
    When a win of "2500" is credited twice for round "r1" under the same key
    Then the second call returns the balances the first call returned
    And the second call writes nothing

  Scenario: a win for a round with no open bet is refused
    Given no open bet for round "r9"
    When a win of "100" is credited for round "r9"
    Then the win is refused as "round not found"

  Scenario: a bet of zero is accepted, funds nothing and opens its round
    When the player places a bet of "0" in round "r0"
    Then the bet is accepted
    And the bet funds nothing
    And round "r0" is open
