Feature: Configuring a brand, its payments and its team
  Administrators see which values differ from the defaults, try a routing rule before deploying
  it, and cannot hand out more than they hold.

  Scenario: a configuration value that differs from the brand's default is marked
    Given a brand whose withdrawal turnover multiplier is set to 3, where its default is 1
    When a brand administrator opens the brand's withdrawal settings
    Then the withdrawal turnover multiplier is marked as differing from the default

  Scenario: a routing rule is simulated before it is deployed and can be rolled back
    Given a draft routing rule
    When a payments administrator simulates it, deploys it and rolls it back
    Then simulating it showed which terminal each deposit it was tried with would reach
    And the rule in force is the one before it

  Scenario: a payments operator is not offered a payments role above their own, and asking for it anyway is refused
    Given an operator holding the "resource_operator" role
    When the operator starts an invitation
    Then the "resource_admin" role is not offered, and the page says it is not below the operator's own
    And an invitation with the "resource_admin" role asked for directly, not through the page, is refused because the role is above the operator's own
