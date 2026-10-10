Feature: An operator lists a sub-brand's payments and reads their summary
  The shared payments lists a sub-brand's payments newest first with a search, a status filter and
  pages, and shows totals: counts by status, captured volume per currency, a daily series and a
  series per payment provider. An operator sees only the sub-brands their level covers.

  Scenario: payments filtered to captured are listed newest first with their fields and the counts by status
    Given a sub-brand with three captured, one failed and one expired payment
    When an operator lists its payments filtered to "captured"
    Then three rows are listed newest first with amount, currency, status, payment provider and the provider's reference
    And the counts by status say three, one and one

  Scenario: the summary sums captured volume per currency, counts the daily and per-provider series, and counts a captured row with no amount separately
    Given a sub-brand with three captured, one failed and one expired payment
    When an operator reads its summary
    Then the captured volume per currency is the sum of the three in minor units
    And the daily series and the series per payment provider count them
    And a captured row with no amount is counted separately rather than summed

  Scenario: an operator of one company is refused the payments of a sub-brand of another company
    Given an operator at the level of one company
    When they list the payments of a sub-brand of another company
    Then they are refused

  Scenario: a sub-brand with no parent is served to the installation level only
    Given a sub-brand with no parent
    When an operator below the installation level lists its payments
    Then they are refused
