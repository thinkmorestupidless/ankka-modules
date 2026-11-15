Feature: Which operators may read presence and change the CRM
  An operator's roles decide each action at engagement, from the role matrix the contract holds.

  Scenario Outline: each action on engagement is admitted to the roles the role matrix names and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin"
    When each asks for the action "<action>" on engagement
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read who is online | admitted | admitted | admitted | admitted |
      | retry or discard a parked send | refused | refused | refused | admitted |
      | change the CRM configuration | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on engagement
    Given an operator of the brand holding only the payments role "resource_admin"
    When they ask for any action on engagement
    Then they are refused, naming a role that would admit them

  Scenario: a platform administrator is admitted to every action on engagement
    Given an operator holding the role "platform_admin"
    When they ask for any action on engagement
    Then they are admitted to every action on engagement
