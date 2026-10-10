Feature: An operator sees and changes a player's protections
  An operator opens a player's responsible gambling tab: limits with usage, pending changes, the
  break, the exclusion, the cases and the history. Operator changes apply at once and are recorded
  against the operator.

  Background:
    Given a player of brand "goldvibe"

  Scenario: an operator setting a limit with a pending raise applies it at once, cancels the pending change and is named in the history
    Given the player has a pending change raising a limit
    When an operator sets that limit
    Then it applies at once
    And the pending change is cancelled
    And the history names the operator

  Scenario: an operator's session limit below the brand's minimum is accepted, because the minimum binds players and not operators
    Given a brand whose minimum session limit is "60" minutes
    When an operator sets the player's session limit to "30"
    Then it is accepted

  Scenario: an operator removing a limit leaves no limit of that type at once
    Given the player has a daily deposit limit
    When an operator removes it
    Then no daily deposit limit applies at once

  Scenario: a case an operator opened with a note and closed with a reason shows both, the note as personal data
    Given a case an operator opened on the player with a note
    When they close it with a reason
    Then the case shows the note and the reason
    And the note is personal data under the player

  Scenario: an operator's action on a player's protections is audited with the before and after
    Given the player's protections
    When an operator changes any of them
    Then an audit record names the operator, the player, the action and the before and after

  Scenario: a change to the brand's minimum session limit binds the next player change and alters no existing limit
    Given a brand setting of a minimum session limit
    When an operator changes it
    Then it binds the next player change
    And no existing limit is altered
