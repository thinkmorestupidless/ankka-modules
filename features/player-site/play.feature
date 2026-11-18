Feature: A player launches a game only when the brand allows it
  The play behaviour is a player's picking, launching and leaving a game as steps. A visitor is asked
  to register; a player with no playable funds is taken to the deposit; the brand may refuse the
  launch by the play gate, a break, the session limit, the player status or the rate limit, and a
  refusal is a step the behaviour cannot go past. A launched game is shown under a game session that
  ends when the player leaves, and is paused at each reality check.

  Background:
    Given a player site of brand "guyanabet"
    And a game "pools" in the catalogue of brand "guyanabet"

  Scenario: a visitor who picks a game is asked to register
    Given a visitor of the player site
    When they pick the game "pools"
    Then they are asked to register
    And no launch is asked of the brand

  Scenario: a signed-in player with no playable funds is taken to the deposit
    Given a player signed in at the player site with no playable funds for the game "pools"
    When they pick the game "pools"
    Then the deposit behaviour stands at the amount
    And no launch is asked of the brand

  Scenario Outline: playable funds are a real balance, a restricted balance or free rounds for the game
    Given a player signed in at the player site
    And their real balance is "<real>" minor units, their restricted balance is "<restricted>" minor units and they hold free rounds for <free rounds>
    When they pick the game "pools"
    Then the game is <launched>

    Examples:
      | real | restricted | free rounds        | launched     |
      | 100  | 0          | no game            | launched     |
      | 0    | 100        | no game            | launched     |
      | 0    | 0          | the game "pools"   | launched     |
      | 0    | 0          | the game "other"   | not launched |
      | 0    | 0          | no game            | not launched |

  Scenario: balances not yet read count as playable funds
    Given a player signed in at the player site whose balance has not been read
    When they pick the game "pools"
    Then the game is launched

  Scenario: a launch the brand answers stands at playing with the game's address and the game session
    Given a player signed in at the player site with playable funds
    When they pick the game "pools"
    Then the brand is asked once to launch the game "pools" in the player's currency
    And the play behaviour stands at playing with the game's address and the game session

  Scenario Outline: a launch the brand refuses stands at refused naming the reason and the page is given no game address
    Given a player signed in at the player site with playable funds
    And the brand refuses the launch as "<code>"
    When they pick the game "pools"
    Then the play behaviour stands at refused with the code "<code>" and <fields>
    And the page is given no game address

    Examples:
      | code                  | fields                                   |
      | PLAY_BLOCKED_KYC      | the KYC status                           |
      | cooling_off_active    | the end of the break                     |
      | session_limit_reached | the session limit and the minutes used   |
      | account_restricted    | the player status                        |
      | RATE_LIMITED          | the time to wait                         |
      | service_unavailable   | the service that did not answer          |

  Scenario: a launch in fun mode needs a sign-in and no funds
    Given a player signed in at the player site with no playable funds for the game "pools"
    When they launch the game "pools" in fun mode
    Then the brand is asked once to launch the game "pools" in fun mode
    And the play behaviour stands at playing

  Scenario: a visitor who launches a game in fun mode is asked to sign in
    Given a visitor of the player site
    When they launch the game "pools" in fun mode
    Then they are asked to sign in

  Scenario: a game not offered in the player's country is not shown and its game session is ended
    Given a player signed in at the player site with playable funds whose country is "GF"
    And the catalogue says the game "pools" is prohibited in "GF"
    When they pick the game "pools"
    Then the play behaviour stands at refused with the code "blocked_in_territory"
    And the page is given no game address
    And any game session opened for the launch is ended

  Scenario: a player of unknown country is not blocked by territory
    Given a player signed in at the player site with playable funds whose country is unknown
    When they pick the game "pools"
    Then the play behaviour stands at playing

  Scenario Outline: leaving a game ends its game session
    Given a player playing the game "pools" at the player site
    When they <leave>
    Then the game session is ended as the player's leaving

    Examples:
      | leave            |
      | close the game   |
      | leave the page   |
      | sign out         |

  Scenario: a reality check falls due after its interval of the game being shown
    Given a player playing the game "pools" at the player site with a reality check of "30" minutes
    When the game has been shown for "30" minutes
    Then the play behaviour stands at the reality check with the time played

  Scenario: keeping on at a reality check records the acknowledgement and schedules the next
    Given the play behaviour stands at the reality check
    When the player keeps on
    Then the acknowledgement is recorded with the game session
    And the play behaviour stands at playing
    And the next reality check falls due after its interval

  Scenario: stopping at a reality check ends the game session
    Given the play behaviour stands at the reality check
    When the player stops
    Then the game session is ended as the player's leaving

  Scenario Outline: no reality check fires when none is set or it could not be read
    Given a player playing the game "pools" at the player site
    And their reality check is <state>
    When the game has been shown for "60" minutes
    Then the play behaviour stands at playing

    Examples:
      | state      |
      | not set    |
      | unreadable |

  Scenario: time the game is hidden does not count toward a reality check
    Given a player playing the game "pools" at the player site with a reality check of "30" minutes
    And the game was shown for "20" minutes and then hidden for "40" minutes
    When the game is shown again for "10" minutes
    Then the play behaviour stands at the reality check with "30" minutes played

  Scenario: hiding and showing a game does not start a reality check's interval again
    Given a player playing the game "pools" at the player site with a reality check of "30" minutes
    And the game was shown for "29" minutes
    When the game is hidden and shown again for "1" minute
    Then the play behaviour stands at the reality check

  Scenario: a second game picked while one is open is launched under its own game session and the first is hidden
    Given a player playing the game "pools" at the player site
    And a game "wheel" in the catalogue of brand "guyanabet"
    When they pick the game "wheel"
    Then the game "wheel" is launched under a game session of its own
    And the game "wheel" is shown and the game "pools" is hidden with its game session still open

  Scenario: picking a game that is already open shows it and launches nothing
    Given a player with the games "pools" and "wheel" open and "wheel" shown
    When they pick the game "pools"
    Then the game "pools" is shown and the game "wheel" is hidden
    And no launch is asked of the brand

  Scenario: closing the shown game ends its game session and shows the next open game
    Given a player with the games "pools" and "wheel" open and "wheel" shown
    When they close the game "wheel"
    Then the game session of the game "wheel" is ended as the player's leaving
    And the game "pools" is shown

  Scenario: a reality check counts the time shown across the open games
    Given a player with a reality check of "30" minutes and the games "pools" and "wheel" open
    And the game "pools" was shown for "20" minutes
    When the game "wheel" is shown for "10" minutes
    Then the play behaviour of the game "wheel" stands at the reality check
    And the play behaviour of the game "pools" stands at playing

  Scenario: a game whose game session the brand ended stands at ended at its next bet check
    Given a player playing the game "pools" at the player site
    And the brand ended the game session of the game "pools"
    When the play behaviour next reads the game session
    Then the play behaviour stands at ended
    And no end of the game session is asked of the brand

  Scenario: signing out ends the game session of every open game
    Given a player with the games "pools" and "wheel" open
    When they sign out
    Then the game sessions of both games are ended as the player's leaving
