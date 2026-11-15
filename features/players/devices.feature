Feature: An operator sees the devices a player has logged in from
  Each seen device is kept as personal data under the player: when it was first and last seen, its
  platform, its browser and the country it logged in from.

  Scenario: a log-in from a new device adds a seen device
    Given a player who has logged in from one device
    When the player logs in from another device
    Then the player has two seen devices
    And the new one carries when it was first seen, its platform, its browser and its country

  Scenario: a log-in from a seen device moves only when it was last seen
    Given a player who has logged in from one device
    When the player logs in from that device again
    Then the player has one seen device
    And when it was last seen is the time of this log-in

  Scenario: a player's seen devices are erased with the player
    Given a player with two seen devices
    When the player is erased
    Then no seen device of the player can be read
