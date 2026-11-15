Feature: Reviewing a withdrawal request from request to payment
  A finance operator reviews a withdrawal request on one page that tells its whole story, and is
  offered only what its state allows.

  Scenario: a withdrawal request's page shows the amounts requested, held, paid and voided, and why
    Given a withdrawal request of "500.00 EUR" of which "200.00 EUR" was voided by a reward's cap
    When a finance operator opens the withdrawal request
    Then the operator sees "500.00 EUR" requested, "300.00 EUR" held and "200.00 EUR" voided
    And the operator sees that the reward's cap voided it

  Scenario Outline: a withdrawal request offers only the actions its state allows and says what each does
    Given a withdrawal request that is "<state>"
    When a finance operator opens the withdrawal request
    Then the operator is offered "<actions>"
    And each action says what it will do before it is done

    Examples:
      | state                   | actions                                   |
      | pending review          | approve, reject                           |
      | awaiting manual payment | confirm the payment, mark the payment failed |
      | needs review            | confirm the payment, mark the payment failed |
      | completed               | nothing                                   |
      | rejected                | nothing                                   |

  Scenario: every state a withdrawal request passed through is shown with who moved it
    Given a withdrawal request approved by one operator and confirmed paid by another
    When a finance operator opens the withdrawal request
    Then each state it passed through is shown with the time and the operator or system that moved it

  Scenario: a player's withdrawals list holds requests in every state
    Given a player with withdrawal requests that are pending review, completed, rejected and cancelled
    When an operator lists the player's withdrawal requests
    Then all four are listed
