Feature: The simulator's operator side behaves as the game provider's
  A brand's catalogue is seeded from the simulator's list of games; an operator provisions free
  rounds for players through the simulator; a tester spends a free round and the brand sees a
  zero-stake debit followed by a credit.

  Background:
    Given a brand configured with the simulator as its game provider

  Scenario: the simulator's list of games is answered in the provider's shape with the three games the original's stub advertises
    Given the simulator's list of fake games
    When the brand asks for the list of games
    Then it receives games, developers and categories in the game provider's shape
    And the three games the original's stub advertises are among them, with their bonus types, game features and themes

  Scenario: free rounds provisioned on a game the simulator has are stored as one instance per player and answered ok with the instances
    Given a request to provision free rounds on a game the simulator's list has
    When it is received
    Then the simulator stores one instance per player with the count remaining
    And it answers "ok" with the instances

  Scenario: free rounds provisioned on a game the simulator does not have are answered error with unknown game
    Given a request to provision free rounds on a game the simulator's list does not have
    When it is received
    Then it answers "error" with "unknown_game"

  Scenario: a free round spent by a player with an active instance sends a free debit of zero and then a credit of the win, and decrements the instance
    Given a player with an active free rounds instance on the session's game
    When the tester spends a free round
    Then the simulator sends a debit of "0.00" of kind "free_debit" and then a credit of the win on the same round
    And the instance is decremented, and marked "used" at zero

  Scenario: a free round spent by a player with no active instance is refused and nothing is sent
    Given a player with no active free rounds instance for the session's game
    When the tester spends a free round
    Then the simulator refuses with "no_free_rounds"
    And it sends nothing

  Scenario: an operator cancelling one player's free rounds instance marks it canceled and later spends are refused
    Given a provisioned free rounds instance of a player
    When the operator cancels it
    Then the instance is "canceled"
    And later spends are refused
