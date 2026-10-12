Feature: A player launches a game and the game provider opens the session
  A signed-in player picks a game. The brand checks they may play, issues a one-use launch ticket,
  asks the game provider to launch the game for them, and hands the frontend the game's address and
  a session id. The game then presents the ticket back; the brand answers the player's details and
  the ticket becomes the session ticket for the rest of the game session.

  Scenario: an eligible player's launch answers the game's address, a session id and a launch ticket, and opens a game session
    Given an eligible player at a brand that holds credentials for the game provider
    When the player launches a game
    Then they receive the game's address from the game provider, a session id and a launch ticket
    And a game session is open for them

  Scenario: a check of an unused launch ticket for its own game is answered ok with the player's details and marks the ticket used
    Given a launch ticket issued for game "slot-x" and not yet used
    When the game provider checks the ticket naming game "slot-x"
    Then it is answered "ok" with the same ticket as the session ticket, the player's currency and country, the player id, and the site's lobby and deposit addresses
    And the launch ticket is marked used

  Scenario: a launch ticket already used is answered session expired when checked again
    Given a launch ticket already used
    When the game provider checks it again
    Then it is answered "session_expired"

  Scenario: a launch ticket checked for another game than it was issued for is answered session expired
    Given a launch ticket issued for game "slot-x"
    When the game provider checks it naming game "slot-y"
    Then it is answered "session_expired"

  Scenario Outline: a suspended or self-excluded player's launch ticket is answered player locked
    Given a launch ticket of a player who is <state>
    When the game provider checks the ticket
    Then it is answered "player_locked"

    Examples:
      | state         |
      | suspended     |
      | self-excluded |

  Scenario: a player whose brand requires KYC before play and whose KYC is not verified is refused at launch naming their KYC status
    Given a player at a brand whose play gate requires KYC before play, whose KYC is not verified
    When the player launches a game
    Then the launch is refused as blocked by the play gate, naming their KYC status

  Scenario Outline: a player cooling off or at their session limit is refused at launch naming the reason and no ticket is issued
    Given a player who is <state>
    When the player launches a game
    Then the launch is refused naming the reason
    And no launch ticket is issued

    Examples:
      | state                                 |
      | cooling off                           |
      | at their session limit for the day    |

  Scenario: a launch of a game whose game provider the brand holds no credentials for is refused as not configured
    Given a player at a brand that holds credentials for one game provider and none for another
    When the player launches a game of the second game provider
    Then the launch is refused as not configured
    And no launch ticket is issued

  Scenario: a launch names the game by its game provider and code and asks that game provider to launch it
    Given a player at a brand that holds credentials for two game providers
    When the player launches a game of the second game provider
    Then the second game provider is asked to launch it under the brand's credentials for it
    And the launch ticket names that game provider and the game's code

  Scenario: a launch ticket not yet checked stays valid however long ago it was issued
    Given a launch ticket issued for game "slot-x" long ago and not yet used
    When the game provider checks the ticket naming game "slot-x"
    Then it is answered "ok"
    And the launch ticket is marked used

  Scenario: a provider call under a session ticket whose game session has ended is served as any other
    Given a session ticket whose game session the frontend has ended
    When the game provider debits "1.00" under the session ticket
    Then it is served as any debit is

  Scenario Outline: a player may launch twenty games in a minute and the twenty-first is refused as too many requests naming the seconds to wait
    Given a player who launched <launched> games within the last minute
    When the player launches another game
    Then <outcome>

    Examples:
      | launched | outcome                                                                                    |
      | "19"     | the launch is served                                                                       |
      | "20"     | the launch is refused as too many requests, naming the seconds until they may launch again |

  Scenario: a launch in fun mode is marked so to the game provider and carries no free rounds
    Given a player launching a game in fun mode
    When the game provider is asked to launch it
    Then the launch is marked fun mode
    And no free rounds are attached to it

  Scenario: a launch naming no free rounds attaches the oldest eligible free rounds to the launch ticket
    Given a player with eligible free rounds for a game
    When the player launches that game naming no free rounds
    Then the oldest eligible free rounds are attached to the launch ticket and echoed back
