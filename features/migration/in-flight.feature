Feature: Nothing in flight is lost by the migration
  A withdrawal the operator approved on Friday is with the provider when the cutover happens. On
  Monday the provider answers, the request completes, and the player's hold is confirmed. A reward
  claimed on Sunday with its deposit window still open can still be activated on Monday.

  Background:
    Given the migration of brand "spinvibe" from the original

  Scenario: a withdrawal payment initiated at a provider before the cutover completes once the provider reports success
    Given a withdrawal request initiated at a provider with reference "UF-991"
    When the migration has run and the provider reports success
    Then the request completes
    And the hold is confirmed
    And the "withdrawal_completed" fact is emitted once

  Scenario: a withdrawal payment initiated before the cutover fails and releases the hold when the provider reports failure
    Given a withdrawal request initiated at a provider with reference "UF-991"
    When the migration has run and the provider reports failure
    Then the hold is released
    And the request is failed, as for a request that began on the rebuild

  Scenario: a reward claimed before the cutover keeps its clocks at the same instants and expires or activates exactly when it would have
    Given a reward in "claimed" with "2" days of its deposit window left
    When the migration has run
    Then its clocks are the same instants
    And it expires or activates exactly when it would have

  Scenario: the migration refuses to export while the wallet reports open bets, and shows the operator whose rounds are still open
    Given a game session open at the freeze
    When the freeze begins
    Then the migration refuses to export until the wallet reports zero open bets
    And the operator sees which players' rounds are still open

  Scenario: a withdrawal request pending review before the cutover is in the new queue with its time, amount and destination for the operator to decide
    Given a withdrawal request pending review
    When the migration has run
    Then it is in the queue with its request time, amount and destination
    And the operator's approval and rejection act on it
