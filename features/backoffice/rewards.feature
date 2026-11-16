Feature: Managing a player's rewards
  An operator sees each restricted funds record in the glossary's words, is offered only what its
  state allows with its effect stated, and is told why a reward is refused.

  Scenario Outline: a restricted funds record shows its state, wagering progress and open rounds, and only its state's actions
    Given a restricted funds record that is "<state>"
    When an operator opens the player's rewards
    Then the record shows its state, its wagering progress and its open round count
    And the operator is offered "<actions>"

    Examples:
      | state             | actions          |
      | active            | forfeit, reclaim |
      | awaiting its cap  | nothing          |
      | forfeited         | nothing          |
      | closed            | nothing          |

  Scenario: a refused reward shows the reason and what would allow it
    Given a player flagged as a suspected duplicate
    When an operator awards the player an offer
    Then the award is refused because the player is a suspected duplicate
    And the operator is shown that clearing the flag, or the brand turning duplicate detection off, would allow it

  Scenario: changing an offer other offers depend on names them before it is changed
    Given an offer that two other offers exclude
    When an operator withdraws the offer
    Then the operator is shown the two offers before it is withdrawn
