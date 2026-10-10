Feature: A deposit is confirmed and turnover is accounted
  A captured payment reaches the wallet once, credits real balance, and records the deposit's
  turnover terms. Bets funded from real balance reduce the turnover still pending; a withdrawal is
  blocked while any is pending.

  Scenario: a confirmed deposit credits real balance and records its required turnover
    Given a player with a wallet in "EUR" and a real balance of "0"
    And the brand's turnover multiplier is "1"
    When a deposit of "5000" under payment reference "p1" is confirmed
    Then the real balance is "5000"
    And a deposit record holds a required turnover of "5000" with status "pending"
    And deposit turnover pending is "5000"

  Scenario: a deposit confirmed again under the same payment reference moves nothing
    Given a player with a wallet in "EUR" whose deposit under payment reference "p1" is confirmed
    When a deposit under payment reference "p1" arrives again
    Then the first answer is returned
    And no transaction is written

  Scenario: a deposit with a turnover multiplier of zero is cleared at once
    Given a player with a wallet in "EUR" and a real balance of "0"
    And the brand's turnover multiplier is "0"
    When a deposit of "5000" under payment reference "p2" is confirmed
    Then the deposit record is "cleared"
    And deposit turnover pending does not change

  Scenario: a bet's real leg reduces deposit turnover pending
    Given a player with a wallet in "EUR" and deposit turnover pending of "5000"
    When the player places a bet with a real leg of "2000"
    Then deposit turnover pending is "3000"

  Scenario: a bet funded only from restricted funds leaves deposit turnover pending unchanged
    Given a player with a wallet in "EUR" and deposit turnover pending of "5000"
    When the player places a bet funded only from restricted funds
    Then deposit turnover pending is "5000"

  Scenario: a rollback does not restore deposit turnover pending
    Given a player with a wallet in "EUR" whose bet with a real leg of "2000" reduced deposit turnover pending to "3000"
    When that bet is rolled back
    Then deposit turnover pending is "3000"

  Scenario: a deposit while a converted record awaits its cap advances that record's deposits since conversion
    Given a player with a wallet in "EUR" and a restricted funds record awaiting its cap with deposits since conversion of "0"
    When a deposit of "1000" under payment reference "p3" is confirmed
    Then the record's deposits since conversion is "1000"

  Scenario: a first deposit in a currency creates the wallet for that currency
    Given a player with no wallet in "USD"
    When a deposit of "1000" in "USD" under payment reference "p4" is confirmed
    Then the player has a wallet in "USD" created with that deposit
