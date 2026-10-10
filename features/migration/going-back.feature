Feature: The operator can go back to the original within the cutover window
  Reconciliation finds wallets the migration cannot explain. The operator stops, puts the
  addresses back, and the original serves Monday's players as if nothing happened. Nothing on the
  original was written during the window.

  Background:
    Given the migration of brand "spinvibe" from the original

  Scenario: going back at the reconciliation step returns the addresses to the original, leaves its data unchanged since the freeze, and keeps the new brand for study
    Given a cutover at the reconciliation step with an "UNEXPLAINED" row in the parity report
    When the operator goes back
    Then the addresses are returned to the original within the cutover window
    And the original's data is unchanged since the freeze
    And the new brand is left as it is for study

  Scenario: going back after the addresses switched is still possible while the window is open, with the players' actions since listed for the operator
    Given a cutover that switched the addresses at "02:40"
    When a defect the plan for going back covers is found at "03:10"
    Then going back is still possible, because the original is untouched
    And the actions players took on the rebuild since "02:40" are listed for the operator to apply again or refund by hand

  Scenario: the window closing after sign-off retires the original and makes going back a restore rather than a step
    Given the sign-off recorded
    When the cutover window closes
    Then the original is marked retired
    And its parts are scaled to zero
    And going back is no longer a step of the runbook but a restore
