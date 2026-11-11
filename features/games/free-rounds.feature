Feature: Free rounds reach a house provider game as they reach any game provider's
  A brand creates free rounds for a player on a house provider game; the house provider hands them
  to the game service, which holds the instance and answers its status. A free round is a debit of
  zero of kind free debit followed by a credit, which the brand settles by its reward rules.

  Background:
    Given a house provider serving a brand, with a game service that registered "loteca"

  Scenario: a bonus created for a registered game is handed to the game and answered active with the count the brand asked
    Given a request from the brand to create free rounds on "loteca" for a player, count "1"
    When the house provider receives it
    Then the game service holds one free rounds instance for the player with count "1"
    And the brand is answered "active" with that count

  Scenario: a bonus for an unknown game is answered unknown game
    Given a request from the brand to create free rounds on "scratch", which no game service registered
    When the house provider receives it
    Then the brand is answered "unknown_game"
    And no game service is asked

  Scenario: a cancelled bonus is cancelled at the game and answered cancelled
    Given free rounds the brand created on "loteca"
    When the brand cancels them
    Then the game service's instance is cancelled
    And the brand is answered "cancelled"

  Scenario: a free round is a debit of zero of kind free debit followed by a credit on the same round
    Given a player with an active free rounds instance on "loteca" and a launch ticket carrying the free rounds
    When the game service spends one free round for the player
    Then the house provider sends a debit of "0.00" of kind "free_debit" and then a credit on the same round
    And the instance's count is decremented

  Scenario: a game's free-round instance stays claimable until the game's own deadline
    Given free rounds created with a deadline the brand asked, on a game whose rounds settle "7" days later
    When the game service sets the instance's claim deadline
    Then the claim deadline is no earlier than the brand asked
    And a free round spent before the claim deadline is sent however late its round settles
