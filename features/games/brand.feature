Feature: A brand plays a house provider game exactly as it plays any game provider's
  An operator of a brand adds the house provider as a second game provider of the same protocol,
  under its own name. From then on the brand lists its games, launches them, and sees each round
  move the wallet as any game provider's round does. The brand needs no change beyond the
  credential it holds for the house provider.

  Background:
    Given a brand that holds credentials for one game provider

  Scenario: a brand configures the house provider as a second game provider of the same protocol and lists its games after the refresh
    Given the house provider holds a brand entry for the brand
    When an operator adds the house provider as a second game provider of the same protocol under the name "eitheror"
    And the nightly refresh of the catalogue runs
    Then the house provider's games are in the catalogue under the game provider "eitheror" with the developer "eitheror"

  Scenario: a launch at the brand reaches the provider signed with the brand's operator key and answers the game's page address
    Given a player at the brand and a house provider game in the catalogue
    When the player launches the house provider game
    Then the house provider is asked to launch it under the brand's credentials for it
    And the player receives the game page's address carrying the launch ticket

  Scenario: the game page's load checks the launch ticket with the brand and the game learns the player, brand and currency
    Given a launch ticket issued for a house provider game
    When the game page loads
    Then the house provider checks the launch ticket with the brand in one signed provider call
    And the game service is told the brand, the player id, the currency and the session ticket

  Scenario: a round opened and settled by a game moves the brand's wallet as any game provider's round does and produces one round settled fact
    Given an open session of a house provider game at the brand
    When the game opens a round with a stake of "1.50" and settles it with a win of "3.00"
    Then the brand's wallet holds one bet of "1.50" and one win of "3.00" on the round, keyed by the house provider's transaction ids
    And one round settled fact names the game provider "eitheror", the round and the game

  Scenario: a launch signed with a key that is not the brand's is refused and nothing is stored
    Given a brand entry holding the brand's credential
    When a launch for the brand arrives signed with another credential
    Then the house provider refuses it
    And no session is stored
