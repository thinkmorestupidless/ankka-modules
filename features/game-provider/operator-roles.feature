Feature: Which operators may read rounds and change the game catalogue
  An operator's roles decide each action at the game provider part, from the role matrix the contract holds.

  Scenario Outline: each action on the game provider part is admitted to the roles the role matrix names and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin"
    When each asks for the action "<action>" on the game provider part
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read a player's rounds | admitted | admitted | admitted | admitted |
      | replay or resolve an unacknowledged call | refused | refused | refused | admitted |
      | change a provider's configuration or the game catalogue | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on the game provider part
    Given an operator of the brand holding only the payments role "resource_admin"
    When they ask for any action on the game provider part
    Then they are refused, naming a role that would admit them

  Scenario: a platform administrator is admitted to every action on the game provider part
    Given an operator holding the role "platform_admin"
    When they ask for any action on the game provider part
    Then they are admitted to every action on the game provider part
