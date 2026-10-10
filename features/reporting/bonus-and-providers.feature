Feature: Marketing reads bonus performance and operations the provider report
  A marketing manager runs the bonus report for a period and sees, per offer, rewards, granted
  value, conversions, forfeits and cancellations with the conversion rate. An operations manager
  runs the provider report and sees attempts, completions, value and success rate per payment
  provider, and withdrawal requests, completions and time to completion.

  Background:
    Given brand "goldvibe"

  Scenario: the bonus report shows the summary and one row per offer with the same figures
    Given rewards under two offers in the period
    When marketing runs the bonus report for it
    Then the summary shows total rewards, granted value, converted count, conversion rate, and the forfeited, cancelled and active counts
    And one row per offer shows its name, type and the same figures

  Scenario: the bonus report filtered by one offer shows only that offer, and its export holds the per-offer table
    Given the bonus report
    When marketing filters it by one offer
    Then only that offer's row and figures appear
    And the export holds the per-offer table

  Scenario: the provider report shows deposits and withdrawals per payment provider and marks a provider below ninety-five percent success
    Given deposit attempts and outcomes for two payment providers in the period
    When operations runs the provider report for it
    Then the summary shows attempts, completed deposits, deposit value and the success rate
    And the summary shows withdrawal requests, completed withdrawals and the average hours to completion
    And one row per payment provider shows the same
    And a provider below "95" percent success is marked

  Scenario: a provider report for a period with no attempts shows every rate absent, not a constant
    Given no deposit attempts in the period
    When operations runs the provider report for it
    Then every rate is absent, not "97"
