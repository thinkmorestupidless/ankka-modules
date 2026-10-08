Feature: An operator corrects a balance or a game provider credits one
  An operator credits or debits real balance or restricted funds with a reason; a game provider
  credits a standalone amount that lands on the primary active record if there is one, else on
  real balance.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: an operator's credit to real balance carries its reason and actor
    Given a real balance of "1000"
    When an operator credits "500" to real balance with reason "goodwill"
    Then the real balance is "1500"
    And an "admin_adjust" transaction carries the reason and the actor

  Scenario: an operator's debit above real balance is refused naming both amounts
    Given a real balance of "1000"
    When an operator debits "1500" from real balance with reason "correction"
    Then it is refused as "debit exceeds balance", naming "1500" and "1000"

  Scenario: an adjustment without a reason is refused
    Given a real balance of "1000"
    When an operator credits "500" to real balance with no reason
    Then it is refused as "reason required"

  Scenario: an operator's debit that leaves a record at zero with no open round forfeits it
    Given an active restricted funds record of "800" with no open round
    When an operator debits "800" from restricted funds with reason "correction"
    Then the record is forfeited
    And the restricted balance is "0"

  Scenario: an operator's debit that leaves a record above zero leaves it active
    Given an active restricted funds record of "800" with no open round
    When an operator debits "300" from restricted funds with reason "correction"
    Then the record is active with a balance of "500"

  Scenario: an adjustment to restricted funds with no active record is refused
    Given no active restricted funds record
    When an operator credits "500" to restricted funds with reason "goodwill"
    Then it is refused as "no open restricted funds"

  Scenario: a provider credit lands on the primary active record
    Given an active restricted funds record of "800"
    When a provider credit of "700" arrives
    Then the record's balance rises by "700"

  Scenario: a provider credit with no active record lands on real balance
    Given no active restricted funds record and a real balance of "1000"
    When a provider credit of "700" arrives
    Then the real balance rises by "700"

  Scenario: an adjustment is never counted as a deposit
    Given an "admin_adjust" transaction of "500"
    When deposit totals, GGR or deposit limit usage are computed
    Then the adjustment is not counted as a deposit
