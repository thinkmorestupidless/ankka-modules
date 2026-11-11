Feature: Finance and the trading desk read the brand's prediction markets
  A brand that runs the predictions module asks what its markets did: how much traded, what the
  taker fees earned, what was paid on resolution, which markets were voided or corrected, and who
  traded. The figures are folds of the markets' and trades' facts, per currency, exact to the
  minor unit, and they never enter the casino's figures.

  Background:
    Given brand "goldvibe" whose entitlement runs the "predictions" module, with a taker fee rate of "100" basis points

  Scenario: the predictions summary holds, per currency, contracts traded, notional volume, trades, traders, taker fees, resolution payments and remainders kept, and the markets opened, resolved and voided
    Given a period in which markets opened, traded, resolved and were voided in "EUR"
    When finance reads the predictions summary for it in "EUR"
    Then it holds contracts traded, notional volume, trades, distinct traders, taker fees, resolution payments and remainders kept, each the exact fold of the period's trades
    And the numbers of markets opened, resolved, voided and corrected in the period

  Scenario: the market report holds one row per market with its status and times, volume, trades, traders, open interest, fees and what was paid on resolution
    Given a market that opened, traded "40" contracts at "0.60" in "4" trades between "3" players and resolved
    When an operator with the markets permission reads the market report
    Then the market's row shows its status, its opened, closed and resolved times and its resolution
    And notional volume "24.00", "4" trades, "3" traders, its taker fees and its resolution payments
    And an open market's row shows its open interest at the time of reading

  Scenario Outline: a voided or corrected market shows what its resolution paid and what its correction changed
    Given a market that was <ending>
    When an operator with the markets permission reads the market report
    Then the market's row shows <shown>

    Examples:
      | ending                         | shown                                                         |
      | voided after trading           | void, with each share's equal part paid and the remainder kept |
      | resolved and then corrected before it was final | the outcome first resolved, the corrected outcome and the payments made on it |

  Scenario: the traders report holds one row per player who traded with volume, fees paid, positions paid and net result
    Given a period in which players traded
    When compliance reads the traders report for it in "EUR"
    Then it holds one row per player who traded, with contracts traded, notional volume, taker fees paid, positions paid and net result
    And each row names the player by email, read as erased for an erased player with every figure unchanged

  Scenario: the day-end of a brand running the predictions module holds the day's taker fees, resolution payments and remainders per market, reconciled with the fee account
    Given a reporting day that has closed with trades and a resolution
    When finance reads the day-end
    Then it holds, per currency and market, the day's taker fees, resolution payments and remainders kept
    And the fee account's credits beside the fees, listing any trade whose fee and credit differ or appear in one only

  Scenario: the dashboard shows today's predictions summary against yesterday's to the same hour
    Given trades in "EUR" yesterday before and after the current hour, and today
    When an operator reads the dashboard's predictions summary in "EUR"
    Then it holds today's contracts traded, notional volume, trades, distinct traders and taker fees
    And each against yesterday's to the same hour, with its trend

  Scenario: a market trading changes no figure of the revenue report or the dashboard
    Given the revenue report and the dashboard for today
    When a market trades and resolves today
    Then every figure of the revenue report is unchanged
    And every figure of the dashboard but its predictions summary is unchanged

  Scenario: a brand that does not run the predictions module is shown no predictions summary
    Given brand "silvervibe" whose entitlement does not run the "predictions" module
    When an operator of "silvervibe" asks for the predictions summary
    Then it is refused as not found
    And the dashboard shows no predictions summary
