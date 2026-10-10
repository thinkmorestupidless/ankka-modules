Feature: An operator turns the support agent on, changes its prompt, and reads what it did
  An operator with the brand's role turns the support agent on for the brand, edits its prompt and
  hand-off wording as a new version, and reads the audit of every tool call and decision.

  Background:
    Given brand "goldvibe"

  Scenario: a brand whose support agent is off answers that it is not available and the chat stays hidden
    Given the brand's support agent is off
    When a player opens the chat
    Then the answer is that the support agent is not available for the brand
    And the chat stays hidden

  Scenario: a new prompt version governs the next conversation while conversations in progress keep theirs
    Given an operator sets a new prompt version
    When the next conversation starts
    Then its prompt is the new version
    And conversations in progress keep theirs

  Scenario Outline: a tool call, an approval decision or a guardrail rejection is audited with the session, the tool or guardrail, the redacted arguments and the outcome
    Given the brand's support agent is on
    When <happening>
    Then an audit record names the session, the tool or guardrail, the redacted arguments and the outcome

    Examples:
      | happening                       |
      | a tool is called                |
      | an approval request is decided  |
      | a guardrail rejects a turn      |
