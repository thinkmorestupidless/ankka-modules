Feature: Which operators may change the support agent
  An operator's roles decide each action on the support agent, from the permissions the brand's roles carry; these are the default roles.

  Scenario Outline: each action on the support agent is admitted to the default roles that carry its permission and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin", each carrying its default permissions
    When each asks for the action "<action>" on the support agent
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read a player's conversations | admitted | admitted | admitted | admitted |
      | change the support agent's configuration | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on the support agent
    Given an operator of the brand holding only the payments role "resource_admin"
    When they ask for any action on the support agent
    Then they are refused, naming the permission they lack

  Scenario: a platform administrator is admitted to every action on the support agent
    Given an operator holding the role "platform_admin"
    When they ask for any action on the support agent
    Then they are admitted to every action on the support agent
