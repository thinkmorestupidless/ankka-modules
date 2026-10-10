Feature: An operator reviews the queue and approves or rejects, and a player cancels
  A finance or admin operator of the brand sees pending withdrawal requests with the player's
  identity status, amount, terminal, destination and age; opens one; approves or rejects it with a
  reason. Approval starts the withdrawal payment. Rejection returns the money and records the
  reason. A player cancels a request only while it is pending review.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: an operator with a finance or admin role approves a pending request and the withdrawal payment is pursued
    Given a withdrawal request pending review
    When an operator with a finance or admin role for the brand approves it
    Then the request is approved
    And a "withdrawal_approved" fact is emitted
    And the withdrawal payment is pursued

  Scenario: an operator rejects a pending request with a reason, the held amount returns and the reason is recorded
    Given a withdrawal request pending review
    When an operator rejects it with the reason "mismatched name"
    Then the request is rejected
    And the held amount returns to real balance as a withdrawal return
    And the reason is recorded
    And a "withdrawal_cancelled" fact is emitted naming the operator's action

  Scenario Outline: approving or rejecting a request already approved is refused as not pending
    Given a withdrawal request already approved
    When an operator <action> it again
    Then it is refused as not pending

    Examples:
      | action   |
      | approves |
      | rejects  |

  Scenario: an operator whose roles name another brand is refused this brand's queue
    Given an operator whose roles name another brand
    When they open this brand's queue
    Then they are refused

  Scenario: a player cancels a request pending review and the amount returns
    Given a withdrawal request pending review
    When the player cancels it
    Then the request is cancelled
    And the held amount returns to real balance
    And a "withdrawal_cancelled" fact is emitted

  Scenario: a player cancelling an approved request is refused as not cancellable
    Given a withdrawal request already approved
    When the player cancels it
    Then it is refused as not cancellable
