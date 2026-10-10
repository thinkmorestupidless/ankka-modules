Feature: A player applies a break or excludes themselves through the support agent in one confirmation
  A player asks for a break or to self-exclude. The support agent states the consequence plainly,
  the break's end and that it cannot be shortened, or that the exclusion is permanent, asks once,
  and on confirmation responsible gambling applies it and the player's sessions end.

  Background:
    Given a signed-in player of brand "goldvibe" whose support agent is on

  Scenario: a break asked for shows an approval request stating the end date and that it cannot be shortened, with no softening
    Given the player asks the support agent for a break until a date
    When the approval request is shown
    Then its summary states the end date and that the break cannot be shortened
    And it has no softening phrase

  Scenario: an approved break is held by responsible gambling, ends the player's sessions, and the reply states when they may return
    Given the player approved a break
    When it is applied
    Then responsible gambling holds the break
    And the player's sessions end
    And the reply states when they may return

  Scenario: a self-exclusion asked for shows an approval request saying it is permanent and cannot be reversed, asked once
    Given the player asks the support agent to self-exclude
    When the approval request is shown
    Then its summary says the exclusion is permanent and cannot be reversed
    And exactly one confirmation is asked

  Scenario: an approved self-exclusion ends the player's sessions and the conversation, and no further turn is accepted
    Given the player approved self-exclusion
    When it is applied
    Then the player's sessions end
    And the conversation ends with a closing reply
    And no further turn is accepted on it

  Scenario: a summary that softens an irreversible control is rejected by the output guardrail and the model asked again
    Given the model's summary softens an irreversible control
    When the output guardrail checks it
    Then the reply is rejected naming the guardrail
    And the model is asked again
