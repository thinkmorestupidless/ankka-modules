Feature: Free rounds are created at the game provider on behalf of the brand's rewards
  The brand's rewards ask the game provider, through the one place that holds its credentials, to
  create free rounds for a player and games, to poll them, to cancel them, and to price spin values.
  The game provider reads the player's profile back during creation.

  Scenario: free rounds asked for a player known to the game provider are created under the reward's id and their status reported back
    Given a player known to the game provider
    When the brand's rewards ask for free rounds of "10" rounds on two games
    Then the free rounds are created at the game provider under the reward's id
    And their status is reported back as active, processing, rejected, failed, cancelled or not configured

  Scenario: free rounds asked for a player who never launched make the player known to the game provider first
    Given a player who has never launched a game
    When the brand's rewards ask for free rounds
    Then the player is made known to the game provider first, so that the player profile read is answered "ok"

  Scenario: cancelling active free rounds cancels them at the game provider
    Given active free rounds
    When the brand's rewards cancel them
    Then the free rounds are cancelled at the game provider

  Scenario: spin values asked for game codes and a currency are answered from the game provider and cached for the day
    Given game codes and a currency
    When the brand's rewards ask their spin values
    Then the game provider's spin values are answered
    And they are cached for the day
