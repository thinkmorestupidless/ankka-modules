Feature: A player registers with an affiliate's bonus code
  Some affiliates hand out a bonus code instead of a link. If the code is an affiliate's, the brand
  binds the player to that affiliate exactly as a link would have, and every later fact about the
  player is attributed.

  Background:
    Given a network the brand knows

  Scenario: a bonus code presented at registration binds the player as a tag would
    Given a bonus code the network issued to an affiliate
    When a visitor registers with the bonus code and no tag
    Then the player is bound to that affiliate and link as a tag would bind them, with no click id
    And a "registration" fact is emitted

  Scenario: a bonus code entered after an organic registration within the window binds the player
    Given a player who registered organically
    When they enter an affiliate's bonus code within the attribution window counted from registration
    Then the player is bound to that affiliate
    And a "registration" fact is emitted dated to the binding
    And the player's later facts are attributed

  Scenario: a bonus code entered by a player already bound applies its reward and changes no binding
    Given a player already bound to one affiliate
    When they enter another affiliate's bonus code
    Then the code's reward applies as the brand's reward rules say
    And the binding does not change
