Feature: Reaching out to players who are online
  An operator who asks is told when a player of their brand comes online, and sends them an inbox item with
  the brand's merge tags filled before sending.

  Scenario: an operator who asked is told when a player of their brand comes online
    Given an operator who asked to be told when players of "goldvibe" come online
    When a player of "goldvibe" comes online
    Then the operator is told, and can open the player from what they were told
    And no operator who did not ask is told

  Scenario: an inbox item is previewed with its merge tags filled before it is sent
    Given an inbox item using the player's first name merge tag
    When an operator previews it for a player
    Then the operator sees the inbox item with the player's first name in place of the merge tag
    And the inbox item is not sent until the operator sends it
