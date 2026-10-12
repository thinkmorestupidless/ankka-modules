Feature: An operator lists, replays or resolves provider calls the wallet did not acknowledge
  A provider call that failed to reach the wallet is kept as an unacknowledged call and answered
  "error". The game provider's retry is served afresh; when the game provider gives up, an operator
  lists the brand's unacknowledged calls, replays one against the wallet under its own transaction
  id, or resolves one with a reason when the money was settled another way.

  Background:
    Given a player with a session ticket for a wallet in "EUR"

  Scenario: an operator lists every unacknowledged call with its kind, amount, round and error
    Given a credit "t3" of "12.00" in round "r3" that failed to reach the wallet and was answered "error"
    When an operator lists the brand's unacknowledged calls
    Then "t3" is listed with its kind, amount, round and error

  Scenario: replaying an unacknowledged credit pays it to the wallet under its transaction id and clears it
    Given a credit "t3" of "12.00" in round "r3" that failed to reach the wallet and was answered "error"
    When an operator replays "t3"
    Then the wallet is credited "12.00" keyed "t3"
    And the unacknowledged call is cleared
    And round "r3" records the replay with the operator and the time

  Scenario: a provider retry after an operator's replay is answered from the replay's outcome and the wallet is not called again
    Given an unacknowledged credit "t3" an operator replayed
    When "t3" arrives again
    Then it is answered with the replay's outcome and balance
    And the wallet is not called

  Scenario: resolving an unacknowledged call with a reason clears it, moves no money and records the reason on the round
    Given a credit "t3" of "12.00" in round "r3" that failed to reach the wallet and was answered "error"
    When an operator resolves "t3" with a reason
    Then no money moves
    And the unacknowledged call is cleared
    And round "r3" records the reason, the operator and the time
