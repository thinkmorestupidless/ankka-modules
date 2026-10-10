Feature: Guardrails, limits and hand-off keep the support agent inside its remit
  A turn that tries to turn the support agent against its instructions, or asks about another
  player, or expresses self-harm, is caught before or after the model; a player who sends too
  much, or a brand whose daily budget for the model is spent, gets a fixed reply; a question the
  support agent cannot help with is handed to the support chat.

  Background:
    Given a signed-in player of brand "goldvibe" whose support agent is on

  Scenario: a turn naming another player's id or email address is refused by the input guardrail before any model is called
    Given a turn naming another player's id or email address
    When the input guardrails run
    Then the turn is refused naming the guardrail
    And no model is called

  Scenario: a turn judged an attempt to override the instructions is refused
    Given a turn the judged guardrail scores as an attempt to override the instructions
    When it runs
    Then the turn is refused

  Scenario: a judged guardrail whose judgment provider is unreachable answers unavailable rather than refusing
    Given the judged guardrail's judgment provider is unreachable
    When a turn runs
    Then the player is told the support agent is unavailable
    And the turn is not refused

  Scenario: a turn judged to express self-harm on a brand that opted in is answered with the safeguarding reply, no tool runs, and it is audited
    Given the brand opted into the self-harm guardrail
    When a turn is judged to express self-harm
    Then the reply is the brand's configured safeguarding reply with the support chat and the helpline
    And no tool runs
    And an audit record is written

  Scenario: a reply containing what looks like another player's id or an internal address is rejected by the output guardrail and the model asked again
    Given a reply that contains a value that looks like another player's id or an internal address
    When the output guardrail runs
    Then the reply is rejected
    And the model is asked again
    And on a streamed reply the rejection follows the text

  Scenario: a player sending more than the brand's turns per minute has the next turn refused as too many, with when they may send again
    Given the player sent more than the brand's turns per minute
    When the next turn arrives
    Then it is refused as too many, with when they may send again

  Scenario: a brand whose daily budget for the model is spent answers the fixed unavailable reply naming the support chat
    Given the brand's daily budget for the model is spent
    When the player sends a turn
    Then the reply is the fixed unavailable reply naming the support chat
    And the brand's status says so

  Scenario: the hand-off reply names the support chat and its hours as the brand configured them
    Given the model says it cannot help
    When the hand-off reply is composed
    Then it names the support chat and its hours as the brand configured them
