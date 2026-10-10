Feature: Free rounds are created at the game provider and settled from play
  An operator or the brand's engagement gives a player free rounds on a game. The game provider is
  asked to create them, the player plays them, each settled round accumulates, and on completion
  or expiry the winnings land where the offer says.

  Background:
    Given a player of brand "goldvibe"

  Scenario: free rounds given on an eligible game at an offered spin value are created at the game provider and live with the offer's expiry
    Given a free rounds offer of "20" rounds on an eligible game with a spin value of "0.20" offered by the game provider in "EUR"
    When the free rounds are given
    Then the game provider is asked to create "20" rounds under the reward's id
    And the reward is live with an expiry of the offer's days, "30" by default

  Scenario: a spin value the game provider does not offer is refused naming the values it does
    Given a free rounds offer with a spin value the game provider does not offer
    When the free rounds are given
    Then it is refused as "spin_value_not_offered" with the values the game provider offers

  Scenario: a game that is not eligible is refused
    Given a free rounds offer on a game that is not eligible
    When the free rounds are given
    Then it is refused as "game_not_eligible"

  Scenario: free rounds the game provider does not confirm within the time allowed are voided and the player is told nothing was given
    Given free rounds the game provider does not confirm within the time allowed
    When the polling fails
    Then the reward is voided
    And the player is told nothing was given

  Scenario: a round settled with a win uses one round and accumulates the win
    Given live free rounds of the player
    When a round settles with a win of "150"
    Then the reward has used one round
    And it has accumulated "150"

  Scenario: the same round settling again changes nothing
    Given live free rounds of the player with a round "r1" already settled
    When round "r1" settles again
    Then nothing changes

  Scenario: the last round settling under an offer paying to real balance credits the accumulated amount to real balance
    Given live free rounds whose offer pays winnings to real balance
    When the last round settles
    Then the full accumulated amount is credited to real balance

  Scenario: the last round settling under an offer paying to restricted funds awards the accumulated amount with the offer's multiplier and cap
    Given live free rounds whose offer pays winnings to restricted funds
    When the last round settles
    Then the accumulated amount is awarded as restricted funds with the offer's wagering multiplier and maximum convertible amount

  Scenario: winnings awarded as restricted funds while a reward is already active stack beside it
    Given live free rounds whose offer pays winnings to restricted funds, and an active reward already
    When the last round settles
    Then the award stacks beside the active reward

  Scenario: free rounds past their expiry with rounds unused are expired and whatever accumulated is paid in full
    Given live free rounds past their expiry with rounds unused
    When the expiry passes
    Then the reward is expired
    And whatever accumulated is awarded or credited in full

  Scenario: a round taken before expiry that settles late is still honoured
    Given free rounds already expired
    When a round taken before the expiry settles late
    Then it is still honoured

  Scenario: an operator asking a game's spin values in a currency is answered the game provider's values sorted
    Given a brand with free rounds set up at the game provider
    When an operator asks a game's spin values in "EUR"
    Then the game provider's values are listed sorted

  Scenario: an operator asking spin values on a brand with no free rounds set up is answered not configured
    Given a brand with no free rounds set up at the game provider
    When an operator asks a game's spin values in "EUR"
    Then it is answered "not_configured"
