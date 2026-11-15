Feature: The dashboard and the reports in the backoffice
  The dashboard shows a brand's figures per currency, never summed across currencies, with a map of
  where its players are; reports are exported by the reporting service with their reference number.

  Scenario: the dashboard shows each currency's figures apart
    Given a brand with players in "EUR" and in "BRL"
    When an operator opens the dashboard
    Then the operator sees one set of figures in "EUR" and one in "BRL"
    And no figure sums the two

  Scenario: the map shows players by country for the chosen period
    Given a brand with players in five countries this month
    When an operator opens the dashboard for this month
    Then the map shows players in those five countries and no other

  Scenario: a report exported from the backoffice carries its reference number
    Given a brand's revenue report for last month
    When a finance operator exports it
    Then the export carries the reference number the reporting service gave it
