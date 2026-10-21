Feature: An outcome the receiver could not take is not lost
  The payment provider has already been acknowledged; nothing upstream will deliver again. If the
  cashier or a brand cannot take an outcome, the gateway keeps trying, and if it gives up the
  outcome stays on the provider session, visible, until it is delivered or an operator acts.

  Scenario: an outcome delivered to a receiver answering a server error or not at all is retried with backoff until it answers
    Given the cashier answering a server error or not at all
    When a capture outcome is delivered to it
    Then delivery is retried with backoff
    And it succeeds once the cashier answers

  Scenario: an outcome a receiver refuses as a wrong request is not retried and the provider session shows the refusal
    Given the cashier answering that the request itself is wrong
    When a capture outcome is delivered to it
    Then it is not retried
    And the provider session shows the outcome refused, with the answer

  Scenario: an outcome whose retries are exhausted stays on the provider session as undelivered and is listable
    Given a capture outcome whose retries are exhausted
    When its retry budget is spent
    Then the provider session shows the outcome undelivered, with its kind, its receiver and its content
    And the undelivered outcomes are listable
    And the outcome is re-driven every two minutes until it is delivered or refused

  Scenario Outline: an undelivered outcome is delivered once when an operator re-drives it or the receiver recovers
    Given an undelivered outcome
    When <trigger>
    Then it is delivered once
    And the provider session shows it delivered

    Examples:
      | trigger                                          |
      | an operator re-drives it                         |
      | the receiver recovers and the next re-drive runs |

  Scenario: a refused outcome is left by the automatic re-drive and delivered when an operator re-drives it
    Given a refused outcome
    When the next re-drive runs
    Then it is not delivered
    When an operator re-drives it
    Then it is delivered once
    And the provider session shows it delivered
