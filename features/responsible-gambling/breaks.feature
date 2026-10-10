Feature: A player takes a break or excludes themselves, and an operator does the same for them
  A player sets a break until a date and cannot shorten it. A player excludes themselves
  permanently in one confirmed action. An operator sets a break, lifts one, excludes for at least
  183 days or permanently, and reinstates after the period.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a break set by the player applies at once, opens a case with its end and publishes one case opened fact
    Given the player is active
    When they set a break until "2026-11-01"
    Then the break applies at once
    And a case of type break opens with that end
    And one "rg_case_opened" fact is published for the case

  Scenario: a player asking to end or shorten their break is refused
    Given the player is on a break
    When they ask to end or shorten it
    Then it is refused, because only an operator may lift a break

  Scenario: a break ends by itself when its end passes and its case closes as expired
    Given the player is on a break until "2026-11-01"
    When that moment passes
    Then the break ends by itself
    And the case closes as expired
    And nothing else about the player changes

  Scenario: an operator lifting a break with a reason ends it and closes the case naming them
    Given the player is on a break
    When an operator lifts it with a reason
    Then the break ends
    And the case closes as lifted, naming the operator and the reason
    And the player may sign in

  Scenario: a player excluding themselves permanently is excluded at once with every game session ended
    Given the player is active
    When they exclude themselves permanently
    Then they are excluded at once
    And the brand's player records set their player status
    And a case of type self-exclusion opens
    And every open game session of theirs is ended
    And one "rg_case_opened" fact is published for the case

  Scenario: an operator's exclusion shorter than 183 days is refused naming the minimum
    Given the player is active
    When an operator excludes them for "60" days
    Then it is refused naming the minimum of "183" days

  Scenario Outline: an operator's exclusion of 183 days or permanent is accepted
    Given the player is active
    When an operator excludes them <period>
    Then it is accepted

    Examples:
      | period          |
      | for "183" days  |
      | permanently     |

  Scenario: a reinstatement before an exclusion's period ends is refused naming the end
    Given the player is excluded for "183" days
    When an operator reinstates them before the period ends
    Then it is refused naming the end of the period

  Scenario: a reinstatement after an exclusion's period ends is accepted and the player may sign in
    Given the player is excluded for "183" days and the period has ended
    When an operator reinstates them
    Then it is accepted
    And the player may sign in

  Scenario: a reinstatement of a permanently self-excluded player is refused
    Given the player is permanently self-excluded
    When an operator reinstates them
    Then it is refused

  Scenario: a player both excluded and on a break is answered with the exclusion
    Given the player is both excluded and on a break
    When anything asks about them
    Then the exclusion is the answer

  Scenario: a break ending on a suspended player leaves them suspended
    Given the player is suspended and on a break
    When the break ends
    Then they are still suspended, because ending a break changes no player status
