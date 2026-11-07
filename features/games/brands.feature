Feature: One house provider serves many brands, each held apart
  An operator of the installation adds a brand to the house provider's brand table. A launch is
  admitted only when signed with that brand's credential; a session, a round and a game's state
  belong to one brand; two brands playing the same game share nothing but the code.

  Scenario: an operator adds a brand with its address, site, key, language and games, and the provider admits its launches from then on
    Given a house provider with no brand entry for "goldvibe"
    When an operator adds a brand entry for "goldvibe" with its address, its site, its credential, the language "pt" and the games it may run
    Then a launch from "goldvibe" signed with that credential is admitted
    And the change applies with no redeploy

  Scenario: a launch for a brand the table does not hold is refused
    Given a house provider with no brand entry for "spinvibe"
    When a launch for "spinvibe" arrives
    Then the house provider refuses it

  Scenario: a brand's games list holds only the games the brand may run
    Given a brand entry for "goldvibe" that may run "loteca" and not "scratch"
    When "goldvibe" asks for the list of games
    Then "loteca" is listed and "scratch" is not
    And a launch of "scratch" from "goldvibe" is answered "unknown_game"

  Scenario: a session of one brand is never answered to another brand's game page or callback
    Given an open session of "goldvibe" and a brand entry for "spinvibe"
    When a check, a debit or a credit for that session arrives for "spinvibe"
    Then the house provider refuses it
    And nothing is sent to either brand

  Scenario: an operator's reads of sessions and rounds are per brand
    Given rounds played at "goldvibe" and at "spinvibe"
    When an operator reads the sessions and rounds of "goldvibe"
    Then only the sessions and rounds of "goldvibe" are listed
