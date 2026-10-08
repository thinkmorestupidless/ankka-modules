Feature: Managers run the programme per brand and across brands
  A manager approves and rejects applications, manages plans, settings, managers, adjustments,
  fraud flags, notes and creatives for the brands their roles cover, sees the full ledger, closes
  periods, and reads a read-only view of what an affiliate sees.

  Scenario: a manager for one brand lists only that brand's memberships and facts
    Given a manager for brand "A" only
    When they list accounts, revenue, payouts or fraud flags
    Then only the memberships and facts of brand "A" are listed

  Scenario: a manager's request naming a brand their roles do not cover is refused
    Given a manager for brand "A" only
    When they list the accounts of brand "B"
    Then it is refused

  Scenario: a manager for several brands reads revenue per brand and per currency
    Given a manager for brands "A" and "B"
    When they read revenue with no brand filter
    Then figures are listed per brand and per currency

  Scenario: a plan created or changed applies to the memberships that name it from the next fact onward and is audited
    Given a manager for brand "A"
    When they create or change a plan for brand "A"
    Then it applies to the memberships at brand "A" that name it, from the next fact onward
    And the change is audited

  Scenario: a manager's adjustment is a payable fact, negative for a debit, swept by the next close
    Given a manager for brand "A"
    When they enter a credit or debit adjustment for an affiliate at brand "A" in a currency with a reason
    Then an adjustment fact is written, negative for a debit
    And it is swept by the next close like any payable fact

  Scenario: a fraud flag is raised once per player, brand and reason when a brand's thresholds are crossed
    Given the programme's fraud thresholds for brand "A"
    When a player's bonus cost or chargebacks cross them
    Then a fraud flag is raised once for that player, brand and reason for the manager to review

  Scenario: dismissing a fraud flag tells the affiliate it was reviewed and dismissed
    Given a fraud flag raised at brand "A"
    When a manager dismisses it
    Then the affiliate is told the fraud flag was reviewed and dismissed

  Scenario: a manager's read-only view of an affiliate shows what the affiliate sees and changes nothing
    Given a manager for brand "A"
    When they open an affiliate's read-only view
    Then they see the dashboard, stats, commission, links, payouts and notifications the affiliate would see
    And they can change nothing from there

  Scenario: adding, resetting or revoking a manager for a brand provisions them with the manager role and the brand's role and is audited
    Given a manager with the right role
    When they add a manager for brand "A", reset their password or revoke them
    Then the manager is provisioned with the manager role and the role for brand "A"
    And the action is audited

  Scenario: a change to a brand's programme page is what new applicants see
    Given the programme page of brand "A"
    When a manager changes it
    Then affiliates applying to brand "A" see the new text
