Feature: A withdrawal is held, released, confirmed or reversed
  A hold takes a withdrawal's amount out of real balance while the request is pending. The wallet
  refuses while a request is already pending, while real balance is short, or while deposit
  turnover is pending; otherwise it forfeits every active award by its withdrawal policy, applies a
  clawback once if any policy calls for it, draws the amount down against any converted record's
  cap and voids the excess, and debits real balance. A release returns the money; a confirmation
  closes the hold; a reversal after confirmation returns the money again.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: a hold takes the amount out of real balance and records the pending request
    Given a real balance of "10000", no restricted funds and no deposit turnover pending
    When a hold of "4000" is placed for request "w1"
    Then the real balance is "6000"
    And "w1" is the pending request
    And a withdrawal transaction of "4000" is written

  Scenario: a second hold while a request is pending is refused
    Given a hold is pending for request "w1"
    When a hold of "1000" is placed for request "w2"
    Then it is refused as "withdrawal request already pending"

  Scenario: a hold above real balance is refused
    Given a real balance of "3000"
    When a hold of "4000" is placed for request "w1"
    Then it is refused as "insufficient funds"

  Scenario: a hold while deposit turnover is pending is refused
    Given a real balance of "10000" and deposit turnover pending of "1"
    When a hold of "4000" is placed for request "w1"
    Then it is refused as "withdrawal blocked by turnover"

  Scenario: a hold forfeits an award under a funds and winnings policy, applies its clawback once, and is refused when real balance is then short
    Given an active restricted funds record of "2000" under withdrawal policy "funds_and_winnings" with a clawback of "1500" computed for it
    And a real balance of "5000"
    When a hold of "4000" is placed for request "w1"
    Then the record is forfeited
    And the clawback of "1500" is applied once
    And it is refused as "insufficient after forfeit" because "3500" is less than "4000"

  Scenario: a hold forfeits a record under a funds policy without clawback, leaves a record under no policy untouched, and requires the player's confirmation
    Given active restricted funds records under withdrawal policies "funds" and "none"
    And a real balance of "10000"
    When a hold of "4000" is placed for request "w1"
    Then the record under "funds" is forfeited with no clawback
    And the record under "none" is untouched
    And the hold requires the player's confirmation

  Scenario: a hold forfeits a record under a cancel promotion policy and reports its award for cancellation
    Given an active restricted funds record under withdrawal policy "cancel_promotion"
    And a real balance of "10000"
    When a hold of "4000" is placed for request "w1"
    Then the record is forfeited
    And its award is reported for cancellation with reason "withdrawal"

  Scenario: a hold draws down a converted record's cap, voids the excess and closes the record when its allowance is exhausted
    Given a restricted funds record awaiting its cap with deposits since conversion of "1000" and a remaining allowance of "2000"
    And a real balance of "10000"
    When a hold of "4000" is placed for request "w1"
    Then "1000" is drawn from deposits since conversion and "2000" from the remaining allowance
    And "1000" is voided as "bonus_cap_void"
    And the paid amount is "3000"
    And real balance is debited by the full "4000"
    And the record closes because its allowance is exhausted

  Scenario Outline: a release returns the held amount and clears the pending request
    Given a hold of "4000" is pending for request "w1"
    When request "w1" is released as <outcome>
    Then the real balance rises by "4000" as a "withdrawal_return"
    And no request is pending

    Examples:
      | outcome   |
      | cancelled |
      | rejected  |
      | failed    |

  Scenario: a confirmation clears the pending request with no money movement
    Given a hold of "4000" is pending for request "w1"
    When request "w1" is confirmed
    Then no balance changes
    And no request is pending

  Scenario: a reversal after confirmation returns the amount
    Given request "w1" of "4000" was confirmed
    When request "w1" is reversed
    Then the real balance rises by "4000"

  Scenario: a second reversal returns the first outcome
    Given request "w1" of "4000" was reversed
    When request "w1" is reversed again
    Then the first outcome is returned
    And the real balance is unchanged

  Scenario Outline: a release or confirmation naming a request that is not the pending one is refused
    Given request "w1" is not the pending request
    When request "w1" is <action>
    Then it is refused as "request not pending"

    Examples:
      | action    |
      | released  |
      | confirmed |
