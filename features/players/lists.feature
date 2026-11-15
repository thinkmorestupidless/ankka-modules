Feature: An operator's lists of players and sessions lose no row
  Every list an operator reads is paged by the players part, carries its total, and says how fresh
  its views are, so a player who exists is always reachable.

  Scenario: the player list is paged with its total and every player is reachable
    Given a brand with "10000" players
    When an operator reads the player list page by page
    Then every page carries the total "10000"
    And every player appears on exactly one page, newest first

  Scenario Outline: the player list is filtered by the players part, not by the reader
    Given a brand with players in several countries and currencies
    When an operator reads the player list filtered by "<filter>"
    Then only the matching players are listed, with their total

    Examples:
      | filter                |
      | country               |
      | currency              |
      | potential VIP         |
      | online                |
      | identity status       |
      | birthday within days  |

  Scenario: the player list answers the count of players for each value of a filter when asked
    Given a brand with players in several countries and currencies
    When an operator reads the player list asking for its counts
    Then each filter's values are listed with the number of players matching

  Scenario: a list's status says how fresh its views are
    Given the brand's views are catching up
    When an operator reads the status of the players part
    Then each view is listed with its freshness
    And the answer says the views are catching up

  Scenario: a player's activity is read for a period, page after page, with its total
    Given a player with activity over a year
    When an operator reads the player's activity for one month
    Then only that month's activity is listed, newest first, with its total
    And the next page continues where the first ended

  Scenario: an operator reads a player's notes
    Given a player with two notes, one pinned
    When an operator reads the player's notes
    Then both are listed with who added each, the pinned one first

  Scenario: an operator reads a player session with its game sessions
    Given a player session during which the player opened two game sessions
    When an operator reads the player's player sessions
    Then the player session is listed with both game sessions

  Scenario: an operator reads a game session's bets
    Given a game session with three rounds
    When an operator reads the game session's bets
    Then each round's bet and win are listed

  Scenario: an operator finds a player's bets by round, by transaction or by status, with what was staked and won
    Given a player with settled and unsettled rounds
    When an operator reads the player's bets filtered by status
    Then only the matching bets are listed
    And the answer carries the total staked, the total won and the net
