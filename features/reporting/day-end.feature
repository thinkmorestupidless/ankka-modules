Feature: Finance closes the day and reconciles with the providers
  When a reporting day closes in the brand's zone, the day-end figures are fixed: per currency, the
  day's deposits and withdrawals per payment provider, the day's GGR per game provider and game
  from the provider's rounds beside the wallet's, bonus cost, chargebacks, and the open
  liabilities. Finance reads the day-end the next morning and reconciles it against the providers'
  statements.

  Background:
    Given brand "goldvibe"

  Scenario: the day-end holds, per currency, the providers' deposits and withdrawals, GGR by game provider and by the wallet, bonus cost, chargebacks and the liabilities at close
    Given a reporting day that has closed
    When finance reads the day-end
    Then it holds, per currency, deposits and withdrawals per payment provider with counts and values
    And GGR per game provider and per game from the settled rounds, beside the wallet's GGR from its transactions
    And bonus cost, chargebacks, and the restricted funds liability and pending holds at close

  Scenario Outline: a round whose figures differ between the game provider's record and the wallet's, or present in one only, is listed under differences with both figures
    Given <round>
    When the day-end is read
    Then the round is listed under differences with both figures

    Examples:
      | round                                                                              |
      | a settled round whose stake and win differ from the wallet's facts for it         |
      | a settled round the wallet has no facts for                                        |
      | a round the wallet settled that the game provider's record lacks                   |

  Scenario: a fact for a closed day arriving after the close leaves the day-end unchanged and appears as a correction carried into the next day-end
    Given a fact for a closed day arriving after the close, a late settlement or a reversal
    When it is read
    Then the day-end is unchanged
    And the fact appears as a correction dated when it arrived
    And it is carried into the next day-end's opening

  Scenario: an exported day-end holds every section with the reference number and the close time
    Given the day-end
    When finance exports it
    Then the file holds every section with the reference number and the close time
