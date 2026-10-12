Feature: A repeated provider call is answered as the first was
  The game provider retries any call it got no answer to. A repeat under the same transaction id or
  cancel id is answered with the first outcome and balance without touching the wallet or asking
  responsible gambling again.

  Background:
    Given a player with a session ticket for a wallet in "EUR"

  Scenario: a repeated debit is answered with the first outcome and balance and the wallet is not called
    Given a debit "t1" answered "ok" with balance "95.00"
    When "t1" arrives again
    Then it is answered "ok" with balance "95.00"
    And the wallet is not called

  Scenario: a repeated credit with a different amount is answered with the first outcome and the difference is recorded as a conflicting repeat
    Given a credit "t2" answered "ok"
    When "t2" arrives again with a different amount
    Then the first outcome is answered
    And the difference is recorded as a conflicting repeat

  Scenario: a repeated debit refused as player locked is answered the same and responsible gambling is not asked again
    Given a debit "t4" answered "player_locked"
    When "t4" arrives again
    Then it is answered "player_locked"
    And responsible gambling is not asked

  Scenario: a repeated cancel is answered ok with the same balance
    Given a cancel "c1" answered "ok"
    When "c1" arrives again
    Then it is answered "ok" with the same balance

  Scenario: a retry of a debit that failed to reach the wallet is served afresh and clears the unacknowledged call on success
    Given a debit "t3" that failed to reach the wallet and was answered "error"
    When the game provider retries "t3"
    Then the retry is served afresh
    And on success the unacknowledged call is cleared
