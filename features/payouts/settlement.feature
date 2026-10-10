Feature: The withdrawal payment is made, reconciled, and settled by hand when it must be
  On approval, a terminal with automated withdrawal payment makes one call to the gateway with the
  request id as its key. The gateway answers completed, initiated, in doubt or rejected. Completed
  confirms the hold. Initiated and in doubt wait for the provider's outcome, or ask the provider's
  status until it is final, or, for a provider with no status to ask, mark the request needs review
  after the provider's window. Rejected at initiation, or a terminal without automated withdrawal
  payment, parks the request awaiting manual payment. Nothing in doubt is ever offered for manual
  payment.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: a withdrawal payment the gateway answers completed confirms the hold and completes the request
    Given an approved withdrawal request to a terminal with automated withdrawal payment
    When the gateway answers completed
    Then the hold is confirmed
    And the request is completed
    And a "withdrawal_completed" fact is emitted

  Scenario: an initiated withdrawal payment whose outcome arrives as completed completes the request
    Given a withdrawal payment the gateway answered initiated with the provider's reference
    When the provider's outcome arrives as completed
    Then the request is completed

  Scenario: an initiated withdrawal payment whose outcome arrives as failed returns the held amount and fails the request
    Given a withdrawal payment the gateway answered initiated with the provider's reference
    When the provider's outcome arrives as failed
    Then the held amount returns to real balance
    And the request is failed naming the reason
    And a "withdrawal_failed" fact is emitted

  Scenario: a withdrawal payment in doubt to a polled provider is asked for its status after thirty minutes
    Given a withdrawal payment in doubt to a provider that can be polled
    When "30" minutes pass with no outcome
    Then the gateway is asked for its status

  Scenario: a status that is not final is asked again five minutes later
    Given a withdrawal payment in doubt whose status was asked
    When the status is not final
    Then it is asked again "5" minutes later

  Scenario Outline: a final status settles the request accordingly
    Given a withdrawal payment in doubt whose status was asked
    When the status is <status>
    Then the request is <settled>

    Examples:
      | status    | settled   |
      | completed | completed |
      | failed    | failed    |
      | reversed  | reversed  |

  Scenario: a provider saying the withdrawal payment was never created marks the request needs review and stops the polling
    Given a withdrawal payment in doubt whose status was asked
    When the provider says the withdrawal payment was never created
    Then the request is marked needs review
    And the polling stops

  Scenario Outline: a withdrawal payment in doubt to a provider that only sends webhooks is marked needs review after the provider's window
    Given a withdrawal payment in doubt to a provider "<provider>" that only sends webhooks
    When its window of "<window>" passes with no outcome
    Then the request is marked needs review
    And the queue shows it

    Examples:
      | provider  | window   |
      | cozeni    | 24 hours |
      | gigadat   | 5 days   |
      | morphosis | 5 days   |

  Scenario Outline: a withdrawal payment the gateway cannot answer is in doubt and reconciled, never awaiting manual payment
    Given an approved withdrawal request to a terminal with automated withdrawal payment
    When the withdrawal payment is initiated and the gateway <answer>
    Then the request is in doubt and reconciled
    And never awaiting manual payment

    Examples:
      | answer                       |
      | cannot be reached            |
      | answers something unrecognised |

  Scenario Outline: a request rejected at initiation or to a terminal without automated withdrawal payment awaits manual payment with the money held
    Given <situation>
    When the request is approved
    Then the request is awaiting manual payment
    And the player sees it as approved
    And the money stays held

    Examples:
      | situation                                                                            |
      | a withdrawal request whose withdrawal payment the gateway rejects at initiation     |
      | a withdrawal request to a terminal with no automated withdrawal payment             |

  Scenario Outline: an operator confirms a manual payment and the hold is confirmed
    Given a withdrawal request <state>
    When an operator confirms the manual payment
    Then the hold is confirmed
    And the request is completed

    Examples:
      | state                            |
      | awaiting manual payment          |
      | approved and marked needs review |

  Scenario Outline: an operator marks a manual payment failed and the amount returns
    Given a withdrawal request <state>
    When an operator marks the manual payment failed with a reason
    Then the held amount returns to real balance
    And the request is failed

    Examples:
      | state                            |
      | awaiting manual payment          |
      | approved and marked needs review |

  Scenario Outline: confirming or failing a manual payment for a request in any other state is refused as not awaiting payment
    Given a withdrawal request pending review
    When an operator <action>
    Then it is refused as not awaiting payment

    Examples:
      | action                                      |
      | confirms the manual payment                 |
      | marks the manual payment failed with a reason |

  Scenario: a completed request the provider reports charged back is reversed and the amount returns
    Given a completed withdrawal request
    When the provider reports it charged back
    Then the amount returns to real balance
    And the request is reversed
    And a "withdrawal_reversed" fact is emitted

  Scenario: the same reversal arriving again moves nothing and returns the first outcome
    Given a reversed withdrawal request
    When the same reversal arrives again
    Then nothing moves
    And the first outcome is returned

  Scenario: a reversal for a request not completed is refused as not completed
    Given a withdrawal request not completed
    When a reversal arrives for it
    Then it is refused as not completed

  Scenario: a provider's outcome reporting a request awaiting manual payment completed wins over the manual path
    Given a withdrawal request awaiting manual payment
    When the provider's outcome arrives as completed
    Then the request is completed
