Feature: The original's operators are given their roles at the cutover
  Each operator of the original is given the roles of the role matrix that their original role maps
  to, by a mapping published for review before the migration runs.

  Scenario: the mapping of the original's operators to roles is published for review before the migration runs
    Given the original's operators with their roles
    When the migration's mapping is made
    Then each operator is listed with the roles they would be given
    And no role is given until the mapping is approved

  Scenario Outline: an operator of the original is given the role their original role maps to
    Given an operator of the original holding the role "<original>"
    When the approved mapping is applied at the cutover
    Then the operator holds the role "<given>" for every brand their original level reached

    Examples:
      | original           | given            |
      | resource_admin     | brand_admin      |
      | resource_finance   | brand_finance    |
      | compliance_officer | brand_compliance |
      | support_agent      | brand_support    |

  Scenario: an operator whose original roles map to more than one role is given the support role and listed
    Given an operator of the original holding the roles "resource_finance" and "compliance_officer"
    When the approved mapping is applied at the cutover
    Then the operator holds the role "brand_support"
    And the operator is listed for review
