Feature: A reality check is set, fires and is acknowledged
  A player chooses a reality check interval; the game page pauses play at that interval until they
  acknowledge, and the acknowledgement is recorded. The check is opt-in.

  Background:
    Given a player of brand "goldvibe"

  Scenario Outline: a reality check interval of an allowed value shows on the profile
    Given the player has no reality check
    When they set a reality check interval of "<minutes>" minutes
    Then the profile shows it

    Examples:
      | minutes |
      | 15      |
      | 30      |
      | 45      |
      | 60      |

  Scenario: a reality check interval of a value not allowed is refused naming the allowed values
    Given the player has no reality check
    When they set a reality check interval of "20" minutes
    Then it is refused naming the allowed values

  Scenario: an acknowledgement naming the player's game session is recorded with the time and the session
    Given a reality check interval set
    When the player acknowledges a check naming their game session
    Then the acknowledgement is recorded with the time and the session

  Scenario: removing the reality check leaves the profile showing none and no check fires
    Given a reality check interval set
    When the player removes it
    Then the profile shows none
    And no check fires

  Scenario: a game run with no reality check set fires no check
    Given the player has no reality check
    When a game runs
    Then no check fires
