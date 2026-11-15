Feature: Which operators may read and change a player's protections
  An operator's roles decide each action on a player's protections, from the permissions the brand's roles carry; these are the default roles.

  Scenario Outline: each action on a player's protections is admitted to the default roles that carry its permission and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin", each carrying its default permissions
    When each asks for the action "<action>" on a player's protections
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read a player's limits, breaks and cases | admitted | admitted | admitted | admitted |
      | set or remove a limit | refused | refused | admitted | admitted |
      | start or lift a break | refused | refused | admitted | admitted |
      | record a self-exclusion | refused | refused | admitted | admitted |
      | open, note or close a case | refused | refused | admitted | admitted |
      | change the brand's protection settings | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on a player's protections
    Given an operator of the brand holding only the payments role "compliance_officer"
    When they ask for any action on a player's protections
    Then they are refused, naming the permission they lack

  Scenario: a platform administrator is admitted to every action on a player's protections
    Given an operator holding the role "platform_admin"
    When they ask for any action on a player's protections
    Then they are admitted to every action on a player's protections
