Feature: Who works in the backoffice, in which brand, and what they may do
  An operator signs in once and works in one brand at a time, among the brands their roles name.
  What they may do is decided by the same roles the owning services check, so nothing is offered
  that would then be refused for a role, and every refusal says why.

  Scenario: an operator sees only the brands their roles name and works in one at a time
    Given an operator whose roles name the brands "goldvibe" and "spinvibe"
    When the operator signs in to the backoffice
    Then the operator is offered "goldvibe" and "spinvibe" and no other brand
    And everything the operator sees and does is in the one brand they picked

  Scenario: an operator with no brand role is told which role they need and sees no brand
    Given an operator whose roles name no brand
    When the operator signs in to the backoffice
    Then the operator is told a brand role is needed
    And no brand is shown

  Scenario: an action the operator's roles do not admit is shown with the role it needs and cannot be started
    Given a compliance operator of a brand and a withdrawal request pending review
    When the operator opens the withdrawal request
    Then approving it is shown as needing the finance role
    And the operator cannot start it

  Scenario: a refusal the owning service gives is shown with its reason and the next step, never as a generic error
    Given a reward the brand refuses to award because the player is flagged as a suspected duplicate
    When an operator awards the player the reward
    Then the operator is shown that the player is a suspected duplicate
    And the operator is shown that clearing the flag would allow it

  Scenario: the operator's sign-in never reaches the browser's scripts
    Given an operator signed in to the backoffice
    When the backoffice's page runs in the operator's browser
    Then nothing the page can read holds the operator's sign-in

  Scenario: a support operator reads a player's account and can write only notes and inbox items
    Given a support operator of a brand
    When the operator reads a player
    Then the operator sees the player's account, wallets, rounds, withdrawal requests and rewards
    And the only writes offered are a note and an inbox item

  Scenario Outline: an adjustment is admitted to finance and administrators only, and a limit to compliance and administrators only
    Given an operator of a brand holding the "<role>" role
    When the operator makes an adjustment and sets a limit for a player
    Then the adjustment is "<adjustment>"
    And the limit is "<limit>"

    Examples:
      | role             | adjustment | limit    |
      | brand_support    | refused    | refused  |
      | brand_finance    | admitted   | refused  |
      | brand_compliance | refused    | admitted |
      | brand_admin      | admitted   | admitted |
      | platform_admin   | admitted   | admitted |
