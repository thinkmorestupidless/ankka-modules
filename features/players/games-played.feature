Feature: An operator sees what a player plays
  For each game a player has played: its rounds, what was staked and what was won, in the wallet's
  currency.

  Scenario: a player's games are listed with their rounds, what was staked and what was won
    Given a player who played "12" rounds of one game and "3" of another
    When an operator reads what the player plays
    Then both games are listed, the one with more rounds first
    And each carries its rounds, the total staked and the total won
