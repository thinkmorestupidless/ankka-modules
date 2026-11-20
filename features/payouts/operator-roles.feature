Feature: Which operators may read and review withdrawal requests
  An operator's roles decide each action on a withdrawal request, from the permissions the brand's roles carry; these are the default roles.

  Scenario Outline: each action on a withdrawal request is admitted to the default roles that carry its permission and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin", each carrying its default permissions
    When each asks for the action "<action>" on a withdrawal request
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read the queue and a withdrawal request | admitted | admitted | admitted | admitted |
      | approve or reject a withdrawal request | refused | admitted | refused | admitted |
      | confirm a withdrawal payment or mark it failed | refused | admitted | refused | admitted |
      | change the brand's withdrawal settings | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on a withdrawal request
    Given an operator of the brand holding only the payments role "resource_finance"
    When they ask for any action on a withdrawal request
    Then they are refused, naming the permission they lack

  Scenario: a platform administrator is admitted to every action on a withdrawal request
    Given an operator holding the role "platform_admin"
    When they ask for any action on a withdrawal request
    Then they are admitted to every action on a withdrawal request
