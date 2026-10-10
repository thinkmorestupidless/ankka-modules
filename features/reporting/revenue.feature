Feature: Finance runs the revenue report for a period
  A finance manager picks a period and runs the revenue report: the brand's total stakes, wins,
  GGR, bonus cost, NGR, deposits, withdrawals and net cash flow in one currency, one row per
  reporting day, exportable. The figures are every player's, exact to the minor unit, and the same
  figures the day after.

  Background:
    Given brand "goldvibe" with facts for every player

  Scenario: the revenue report sums every player exactly in the currency asked for and names the currencies it holds
    Given a period
    When finance runs the revenue report for it in "EUR"
    Then total stakes, total wins, GGR, bonus cost, NGR, total deposits, total withdrawals and net cash flow are the exact sums over every player, in "EUR"
    And the report names the currencies it holds figures in

  Scenario: the revenue report has one row per reporting day in the brand's zone, with a quiet day at zero and a late bet on its own day
    Given a period of several days with a bet placed at "23:30" in the brand's reporting zone
    When finance runs the revenue report for it
    Then it has one row per reporting day in the brand's reporting zone
    And a day with no activity shows zero
    And the bet counts on the day it was placed in the brand's reporting zone, not the next day elsewhere

  Scenario: an exported revenue report holds the summary row and every daily row with every column, equal to the screen
    Given the revenue report on screen
    When finance exports it
    Then the file holds the summary row and every daily row with every column
    And the figures equal the screen's

  Scenario: the programme's daily GGR facts for a closed day sum to that day's GGR for the attributed players
    Given the revenue report for a closed day
    When the programme's "ggr-daily" facts for that day are summed over the brand's attributed players
    Then they equal that day's GGR for those players

  Scenario: a revenue report asked for a currency the brand holds no wallet in is refused naming the currencies it holds
    Given a period
    When finance runs the revenue report for it in "JPY", a currency the brand holds no wallet in
    Then it is refused naming the currencies the brand holds

  Scenario: a revenue report run again after the view is rebuilt gives identical figures
    Given the reporting view rebuilt from the brand's facts
    When finance runs the revenue report again for the same period
    Then every figure is identical
