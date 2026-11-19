Feature: A player's sign-in is held by the site server
  A player site is pages in the player's browser and a site server beside the brand's services. The
  site server passes a sign-in to the brand, keeps what the realm issued where no script of the page
  can read it, and makes every call for the page under it. The session behaviour is the player's
  registering, signing in, staying signed in and signing out, as steps a page shows.

  Background:
    Given a player site of brand "guyanabet"
    And a player of brand "guyanabet"

  Scenario: a player who signs in at the player site is signed in at the brand and the page is never given the sign-in
    Given the player is active with a verified email address
    When they sign in at the player site with the right password
    Then a player session opens at the brand
    And the page is told the player id, the currency and the country
    And the sign-in is in no answer to the page, in nothing the page keeps and in nothing the site server logged

  Scenario Outline: a sign-in the brand refuses puts the session behaviour at refused with the brand's code and fields
    Given the brand refuses the player's sign-in as "<code>"
    When they sign in at the player site
    Then the session behaviour stands at refused with the code "<code>" and <fields>
    And the page is given no text of the brand's

    Examples:
      | code                | fields                |
      | invalid_credentials | no fields             |
      | email_not_verified  | no fields             |
      | account_restricted  | the player status     |
      | cooling_off         | the end of the break  |

  Scenario: calls refused because the sign-in lapsed refresh it once and are each made once more
    Given the player is signed in at the player site
    And the sign-in has lapsed
    When the page makes three calls at once
    Then the sign-in is refreshed once
    And each of the three calls is made once more and answered

  Scenario: a sign-in the realm will not refresh signs the player out and the page is told why
    Given the player is signed in at the player site
    And the realm refuses to refresh the sign-in
    When the page makes a call
    Then the player is signed out at the player site
    And the session behaviour stands at signed out with the reason "refresh_expired"

  Scenario: a refresh that fails for another cause keeps the sign-in
    Given the player is signed in at the player site
    And the brand does not answer a refresh
    When the page makes a call whose sign-in has lapsed
    Then the call is refused as unavailable naming the service
    And the player is still signed in at the player site

  Scenario: a player who signs out ends the player session and the site server keeps nothing of the sign-in
    Given the player is signed in at the player site
    When they sign out
    Then the player session is ended at the brand
    And the site server keeps nothing of the sign-in

  Scenario: an idle player is warned and then signed out
    Given the player is signed in at the player site
    When they do nothing for thirty minutes less thirty seconds
    Then the session behaviour stands at the idle warning
    When they do nothing for thirty seconds more
    Then the player is signed out at the player site

  Scenario: a warned player who chooses to stay has their sign-in refreshed
    Given the session behaviour stands at the idle warning
    When the player chooses to stay
    Then the sign-in is refreshed
    And the session behaviour stands at signed in

  Scenario: a player who placed a bet in the last six minutes is not idle
    Given the player is playing a game
    And their game session's last bet was four minutes ago
    When the five-minute check of the game session runs
    Then the sign-in is refreshed
    And the player is not counted idle

  Scenario: a visitor who registers is told to verify their email address and is not signed in
    Given a visitor of the player site
    When they register with an email address, a password and a currency
    Then the session behaviour stands at awaiting verification
    And no player session opens

  Scenario: a page loaded by a signed-in player knows the player before it asks anything
    Given the player is signed in at the player site
    When the page is loaded again
    Then the page knows the player id, the brand, the currency and the country
    And no service was asked

  Scenario: a request for anything the player SDK does not name is refused by the site server
    Given the player is signed in at the player site
    When the page asks the site server for an operator's route
    Then the site server refuses the request
    And no service is asked

  Scenario: a site server refuses a request naming another brand
    When the page asks the site server for a route of brand "goldvibe"
    Then the site server refuses the request
    And no service is asked
