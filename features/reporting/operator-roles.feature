Feature: Which operators may read the dashboard and the reports
  An operator's roles decide each report, from the role matrix the contract holds.

  Scenario Outline: each action on the reports is admitted to the roles the role matrix names and refused to the others
    Given operators of the brand holding each of the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin"
    When each asks for the action "<action>" on the reports
    Then the support operator is "<brand_support>"
    And the finance operator is "<brand_finance>"
    And the compliance operator is "<brand_compliance>"
    And the admin operator is "<brand_admin>"

    Examples:
      | action | brand_support | brand_finance | brand_compliance | brand_admin |
      | read the dashboard | admitted | admitted | admitted | admitted |
      | read a revenue, players, transactions or rewards report | refused | admitted | refused | admitted |
      | read a day-end report | refused | admitted | refused | admitted |
      | read a compliance export | refused | refused | admitted | admitted |
      | ask for a player's dispute report | refused | admitted | admitted | admitted |
      | change the reporting settings | refused | refused | refused | admitted |

  Scenario: an operator holding only a payments role is refused every action on the reports
    Given an operator of the brand holding only the payments role "resource_admin"
    When they ask for any action on the reports
    Then they are refused, naming a role that would admit them

  Scenario: a platform administrator is admitted to every action on the reports
    Given an operator holding the role "platform_admin"
    When they ask for any action on the reports
    Then they are admitted to every action on the reports
