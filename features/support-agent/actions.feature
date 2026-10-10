Feature: A player asks the support agent for an action, confirms it, and the owning part applies it
  A player asks to withdraw, to close an active game session, or to set a deposit limit. The
  support agent states exactly what will happen and the turn ends with an approval request. The
  player approves or cancels through the chat; on approval the tool runs once, the owning part
  applies its own rules, and the support agent reports the outcome.

  Background:
    Given a signed-in player of brand "goldvibe" whose support agent is on, with a wallet in "GBP"

  Scenario: a withdrawal asked for ends the turn with an approval request stating the amount, and nothing is requested yet
    Given the player asks the support agent to withdraw "£50"
    When the model calls the withdrawal tool
    Then the turn ends with an approval request stating "5000" minor units of "GBP" and the summary the player sees
    And nothing has been requested of the brand's withdrawals

  Scenario: approving a withdrawal makes one withdrawal request keyed by the approval and the reply carries its reference
    Given an approval request for a withdrawal awaiting the player's decision
    When the player approves it
    Then the brand's withdrawals receive one withdrawal request keyed by the approval request's id
    And the reply carries the request's reference

  Scenario: cancelling an approval request requests nothing and the reply acknowledges it
    Given an approval request for a withdrawal awaiting the player's decision
    When the player cancels it
    Then nothing is requested
    And the model is told the player cancelled
    And the reply acknowledges it

  Scenario: a new turn sent while an approval request awaits is refused as a conflict naming the request
    Given an approval request for a withdrawal awaiting the player's decision
    When the player sends a new turn instead
    Then it is refused as a conflict naming the pending approval request
    And the approval request still awaits

  Scenario: a withdrawal the brand's withdrawals refuse after approval is explained from the refusal's reason and not requested again
    Given the player approved a withdrawal
    When the brand's withdrawals refuse it for insufficient funds or a gate
    Then the tool's result is the refusal's reason
    And the reply explains it
    And no second request is made

  Scenario: a restart between the decision and the tool's run ends the turn without running the tool again
    Given the player approved an action
    When the support agent restarts between the decision and the tool's run
    Then the turn ends
    And the tool is not run again
    And the player sees the turn ended and may ask again

  Scenario: the same decision arriving again is refused as already decided and nothing runs twice
    Given the player approved a withdrawal
    When the same decision arrives again
    Then it is refused as already decided
    And nothing runs twice

  Scenario: a deposit limit asked for shows whether it applies at once or after the day's rule, as responsible gambling answers
    Given the player asks the support agent to set a daily deposit limit
    When the approval request is shown
    Then its summary states whether the limit applies at once or after the "24" hour rule, as responsible gambling answers
