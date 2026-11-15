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
      | awaiting its cap  | forfeit          |
      | forfeited         | nothing          |
      | closed            | nothing          |

  Scenario: a refused reward shows the reason and what would allow it
    Given a player who has claimed an offer the most times it allows
    When an operator awards the player that offer
    Then the operator is shown that the claim limit is reached
    And the operator is shown that a reward awarded by hand is not limited by it

  Scenario: changing an offer other offers depend on names them before it is changed
    Given an offer that two other offers exclude
    When an operator withdraws the offer
    Then the operator is shown the two offers before it is withdrawn
