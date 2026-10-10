Feature: A player signs in, plays in game sessions, and signs out
  A player signs in with email address and password. The realm checks the password and asks the
  brand whether the account may sign in; the brand opens a player session bound to the realm's
  session, and the player receives their sign-in, their id, currency, country and any bonus code
  still pending from registration. A game session is opened under the player session at launch, and
  ends when the game closes, when the session limit is reached, when the player signs out, or when
  an operator ends it by force.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a sign-in with the right password opens a player session and answers the player's details
    Given the player is active with a verified email address
    When they sign in with the right password
    Then a player session opens recording the realm's session id, the network address and the user agent
    And the answer carries the sign-in, the player id, the currency, the country and the pending bonus code
    And a "player_logged_in" fact is produced with the session id

  Scenario: a sign-in by a player whose email address is not verified is refused and no session opens
    Given the player's email address is not verified
    When they sign in
    Then they are refused as "email_not_verified"
    And no player session opens

  Scenario Outline: a sign-in by a restricted player is refused naming the status and the attempt is kept
    Given the player is <status>
    When they sign in with the right password
    Then they are refused as "account_restricted" naming the status
    And no player session opens
    And the refused attempt's time is kept on the player

    Examples:
      | status        |
      | suspended     |
      | self-excluded |
      | blocked       |

  Scenario Outline: the realm asking whether a restricted player may sign in is answered that they may not, before anything is issued
    Given the player is <status>
    When the realm asks whether the player may sign in
    Then the brand answers that they may not, naming the status
    And the realm refuses before issuing any sign-in

    Examples:
      | status        |
      | suspended     |
      | self-excluded |

  Scenario: a sign-out ends the player session and every game session under it and revokes the realm's session
    Given the player is signed in with two open game sessions
    When they sign out
    Then the player session ends with reason "logout"
    And every game session under it ends with the same reason
    And the realm's session is revoked
    And a "player_logged_out" fact and then each "game_session_ended" fact are produced

  Scenario: a player session whose realm session has gone idle ends as expired when the brand next asks the realm
    Given a player session whose realm session has gone idle
    When the brand next asks the realm for its live sessions
    Then the player session ends with reason "expired"
    And the facts are produced as for a sign-out

  Scenario: a refresh of the sign-in issues a new one
    Given a sign-in that can be refreshed
    When its refresh is presented
    Then a new sign-in is issued

  Scenario Outline: a refresh that is expired, reused or revoked is answered refresh expired
    Given a refresh of the sign-in that is <state>
    When it is presented
    Then it is answered "refresh_expired"

    Examples:
      | state   |
      | expired |
      | reused  |
      | revoked |

  Scenario: a player's request about another player's id is refused as a mismatch
    Given the player is signed in
    When they make a request about another player's id
    Then it is refused as "player_id_mismatch"

  Scenario: a game session opened at launch for an active verified player is recorded under the player session
    Given the player is active and verified with a player session
    When the game provider asks to open a game session for a game and the player's currency
    Then a game session opens under the player session recording the game, the start time and the currency
    And a "game_session_started" fact is produced
    And the id is returned

  Scenario Outline: a game session asked for a restricted or closed player is refused naming the status
    Given the player is <status>
    When the game provider asks to open a game session
    Then it is refused naming the status

    Examples:
      | status        |
      | suspended     |
      | self-excluded |
      | blocked       |
      | closed        |

  Scenario: a game session's timer is set from the minutes left of the session limit
    Given the player has a session limit of "60" minutes and "50" already used today
    When a game session opens
    Then its timer is set for "10" minutes

  Scenario: a game session whose timer fires ends with reason limit
    Given an open game session whose timer was set for "10" minutes
    When the timer fires
    Then the game session ends with reason "limit"
    And the "game_session_ended" fact says so

  Scenario: a player ending a game session records its end time and duration, and ending it again changes nothing
    Given an open game session
    When the player ends it
    Then it records the end time and the duration in whole minutes
    And a "game_session_ended" fact is produced
    And ending it again changes nothing

  Scenario: an operator ending a game session by force ends it with reason forced and is named in the audit record
    Given an open game session
    When an operator of the brand ends it by force
    Then it ends with reason "forced"
    And the audit record names the operator

  Scenario: a player reading their sessions sees their own and nobody else's
    Given the player has game sessions and another player has one
    When the player reads their sessions
    Then they see their own sessions and nobody else's
    And the other player's session is answered "session_not_found"
