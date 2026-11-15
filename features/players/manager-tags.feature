Feature: An operator puts manager tags on a player
  A manager tag is an operator's own short label on a player, such as who looks after them. It is
  read with the player and changes nothing about what the player may do.

  Scenario: an admin operator sets a player's manager tags and they are read with the player
    Given a player with no manager tag
    When an admin operator sets the player's manager tags to "host: ana" and "high value"
    Then the player is read with both manager tags
    And who set them and when is recorded

  Scenario: a manager tag longer than forty characters is refused
    Given a player with no manager tag
    When an admin operator sets a manager tag of "41" characters
    Then the change is refused naming the manager tag
