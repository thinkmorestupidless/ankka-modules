Feature: The catalogue is fed by the game provider and curated by the brand
  Nightly, the brand fetches the game provider's list of games and refreshes its catalogue: new
  games appear, games the provider stops listing are marked removed, a game's own enabled flag is
  kept. An operator disables developers, categories or single games for their brand, or enables a
  game the provider disabled. A player browses the catalogue and sees the popular games.

  Scenario: the nightly refresh puts every game the game provider lists in the catalogue with its details
    Given the game provider's list of games
    When the nightly refresh runs
    Then every game is in the catalogue with its code, name, developer, category, labels, themes, game features, bonus types, restricted and prohibited territories, deprecation date and the game provider's enabled flag

  Scenario: a game the game provider stops listing is marked removed with its enabled flag unchanged
    Given a game in the catalogue the game provider stops listing
    When the nightly refresh runs
    Then the game is marked removed
    And its enabled flag is unchanged

  Scenario: a refresh that fetches nothing marks nothing removed
    Given a catalogue
    When the nightly refresh fetches nothing
    Then nothing is marked removed

  Scenario: no game of a developer the brand disabled is listed to a player
    Given a brand that disabled a developer
    When a player browses the brand's catalogue
    Then no game of that developer is listed, whatever the game's own flag or the brand's own setting for it

  Scenario: a game the game provider disabled is listed when the brand enables it
    Given a game the game provider disabled
    When the operator enables it for the brand
    Then the game is listed

  Scenario: a game marked removed cannot be enabled by the brand
    Given a game marked removed
    When the operator enables it for the brand
    Then it is refused

  Scenario: a game that prohibits or restricts the player's country is not listed to them
    Given a player in a country a game prohibits or restricts
    When the player browses the catalogue with their country
    Then the game is not listed

  Scenario: popular games are ranked by rounds played at the brand and leave out a game disabled since the ranking was built
    Given a brand
    When its popular games are asked
    Then games are ranked by rounds played at that brand
    And a game disabled since the ranking was built is not among them

  Scenario: an operator reads every game of the catalogue, removed and disabled included, with each layer of the enablement decision
    Given a brand
    When an operator reads the brand's catalogue
    Then every game is listed, removed and disabled included
    And each layer of the enablement decision is shown
