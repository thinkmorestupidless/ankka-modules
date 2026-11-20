Feature: Which operators may read and change rewards
  An operator's roles decide each action on a reward, from the permissions the brand's roles carry; these are the default roles.

  Scenario Outline: each action on a reward is admitted to the default roles that carry its permission and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin", each carrying its default permissions
    When each asks for the action "<action>" on a reward
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read the brand's rewards and one reward | admitted | admitted | admitted | admitted |
      | give a reward from an offer or by hand | refused | admitted | refused | admitted |
      | forfeit or reclaim a reward | refused | admitted | refused | admitted |
      | create or change an offer | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on a reward
    Given an operator of the brand holding only the payments role "resource_admin"
    When they ask for any action on a reward
    Then they are refused, naming the permission they lack

  Scenario: a platform administrator is admitted to every action on a reward
    Given an operator holding the role "platform_admin"
    When they ask for any action on a reward
    Then they are admitted to every action on a reward
