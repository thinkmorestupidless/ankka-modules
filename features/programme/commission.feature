Feature: A brand's facts become commission
  A brand emits attribution facts; the programme turns each into the commission the affiliate's
  plan at that brand says, folding the ledger fresh from facts every time so late and out-of-order
  facts correct themselves and nothing is ever counted twice.

  Scenario: a registration fact records the player as attributed to the affiliate at that brand
    Given a "registration" fact naming the programme, an affiliate, a link and a pseudonym
    When it is consumed
    Then the player is recorded as attributed to that affiliate at that brand with the link, the campaign, the sub-ids, the click and the country

  Scenario: a second registration fact for the same player at the same brand is ignored as a duplicate
    Given a player already attributed at brand "A"
    When a second "registration" fact for the same player at brand "A" is consumed
    Then it is ignored as a duplicate

  Scenario: a first deposit at or above the plan's minimum deposit writes one CPA fact in the deposit's currency
    Given an attributed player on a CPA or hybrid plan with a minimum deposit of "2000"
    When a "first-deposit" fact of "2000" arrives
    Then one CPA fact for the plan's amount is written in the deposit's currency, dated when the deposit occurred

  Scenario: a later deposit in any period writes no second CPA fact and is still counted in the player's totals
    Given an attributed player whose first deposit paid CPA
    When a "deposit" fact arrives in a later period
    Then no second CPA fact is written
    And the deposit is counted in the player's totals

  Scenario: a first deposit below the minimum deposit writes no CPA fact and leaves the first-deposit guard unset
    Given an attributed player on a CPA or hybrid plan with a minimum deposit of "2000"
    When a "first-deposit" fact of "1000" arrives
    Then no CPA fact is written
    And the player's first-deposit guard is not set, so a later deposit that meets the minimum deposit pays CPA

  Scenario: GGR, bonus cost and chargeback facts fold into NGR and a RevShare fact for the delta
    Given an attributed player on a RevShare or hybrid plan
    When "ggr-daily", "ggr-adjustment", "bonus-cost" and "chargeback" facts arrive
    Then NGR is folded as GGR minus bonus cost, chargebacks and the brand's admin, payment and provider fee percentages
    And a RevShare fact is written for exactly the delta between the commission on the cumulative NGR and what has already been emitted, positive or negative

  Scenario: without negative carryover a negative period floors RevShare at zero and the next period starts fresh
    Given an attributed player on a plan without negative carryover
    When a period's NGR is negative
    Then that period's RevShare floors at zero
    And the next period starts fresh

  Scenario: with negative carryover a deficit persists until positive NGR covers it
    Given an attributed player on a plan with negative carryover
    When a period's NGR is negative
    Then the deficit persists until positive NGR covers it

  Scenario: a fact whose id was consumed before writes nothing and is counted as a duplicate
    Given a fact whose id has been consumed before
    When it arrives again on any carrier
    Then nothing is written
    And the duplicate is counted

  Scenario: a fact for a player without attribution at that brand is recorded as unattributed and writes no commission
    Given a fact for a player the programme has no attribution for at that brand
    When it is consumed
    Then it is recorded as unattributed for the brand's manager to see
    And no commission is written

  Scenario: a completed withdrawal then its reversal raise and lower the player's withdrawal total, never below zero
    Given a "withdrawal-completed" fact and later a "withdrawal-reversed" fact with the same reference
    When both are consumed
    Then the player's withdrawal total rises then falls by that amount and never below zero

  Scenario Outline: a withdrawal requested or failed changes no total
    Given an attributed player
    When a "<kind>" fact is consumed
    Then no total changes

    Examples:
      | kind                 |
      | withdrawal-requested |
      | withdrawal-failed    |

  Scenario: a reversal without a matching completed withdrawal is ignored
    Given an attributed player with no completed withdrawal
    When a "withdrawal-reversed" fact is consumed
    Then it is ignored
