Feature: Which operators may read and change a player
  An operator's roles decide each action on a player, from the permissions the brand's roles carry; these are the default roles.

  Scenario Outline: each action on a player is admitted to the default roles that carry its permission and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin", each carrying its default permissions
    When each asks for the action "<action>" on a player
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read a player's profile, sessions and activity | admitted | admitted | admitted | admitted |
      | add or pin a note | admitted | admitted | admitted | admitted |
      | send an inbox item | admitted | admitted | admitted | admitted |
      | suspend, reinstate, close or reopen an account | refused | refused | admitted | admitted |
      | change a profile or a date of birth | refused | refused | admitted | admitted |
      | force a session to end | refused | refused | admitted | admitted |
      | clear a no-deposit block | refused | refused | admitted | admitted |
      | review or dismiss a fraud case | refused | refused | admitted | admitted |
      | request an erasure | refused | refused | admitted | admitted |
      | change a VIP tier or a manager tag | refused | refused | refused | admitted |
      | change the brand's sites, countries or currencies | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on a player
    Given an operator of the brand holding only the payments role "fraud_analyst"
    When they ask for any action on a player
    Then they are refused, naming the permission they lack

  Scenario: a platform administrator is admitted to every action on a player
    Given an operator holding the role "platform_admin"
    When they ask for any action on a player
    Then they are admitted to every action on a player
