Feature: A caller lists the brand's games and reads their statistics
  A caller lists the games the brand offers with their catalogue details, and reads games ranked by
  rounds, sessions, players, stake or GGR over the last 1, 7 or 30 days, and one game's figures.
  Figures are computed hourly; every answer says when.

  Scenario: the games listing holds only the games available on the brand, each with its catalogue details
    Given brand "goldvibe" whose catalogue the brand has configured
    When a caller lists games
    Then it receives the games available on the brand in game-code order, each with its catalogue details

  Scenario Outline: a game the brand disabled, the provider withdrew or that is not yet released is not found
    Given a game <state>
    When a caller lists games or reads it by code
    Then it is absent from the listing
    And reading it by code is told it is not found

    Examples:
      | state                       |
      | the brand disabled          |
      | the game provider withdrew  |
      | released only next month    |

  Scenario: a change to the brand's catalogue reaches callers within five minutes
    Given an operator disables a game of brand "goldvibe"
    When five minutes have passed
    Then the game is absent from the listing

  Scenario: games ranked by a figure over a window match a hand count, with when they were computed
    Given rounds across games with known stakes and wins
    And the figures have been computed
    When a caller ranks games by rounds over 7 days
    Then the ranking matches a hand count, in descending order
    And it says when the figures were computed

  Scenario: a money ranking without a currency is refused, and with one ranks in that currency only
    Given rounds in "EUR" and in "BRL"
    When a caller ranks games by stake without a currency
    Then it is refused as an invalid request
    And ranking by stake in "BRL" ranks figures in "BRL" only

  Scenario: a game with no activity in a window reports zeros for it
    Given a game with rounds in the last 30 days and none in the last day
    When a caller reads that game's figures
    Then the 1-day figures are zero
    And the 30-day figures match a hand count

  Scenario: cancelled rounds count nowhere, and free-spins rounds count as rounds with no stake, win or GGR
    Given a cancelled round and a free-spins round of a game
    When the figures are computed
    Then the cancelled round counts in no figure
    And the free-spins round counts as a round with no stake, win or GGR

  Scenario: before the first computation a ranking is empty with no computation time
    Given a brand whose figures have never been computed
    When a caller ranks its games
    Then the ranking is empty
    And it gives no computation time
