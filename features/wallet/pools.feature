Feature: Real balance is two pools, deposited money and converted money
  A wallet's real balance is held as a deposit pool and a converted pool that always sum to it. A
  deposit joins the deposit pool; a conversion joins the converted pool. The real leg of a stake is
  taken from the converted pool first; a win is split in the stake's ratio; a rollback restores the
  parts the bet recorded. A withdrawal's paid amount leaves the deposit pool first, while a cap
  void and a clawback leave the converted pool first. Every movement of real balance records its
  parts and carries them on its fact, so affiliate GGR counts deposit pool money only.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: the real leg of a stake is taken from the converted pool first
    Given a deposit pool of "5000" and a converted pool of "5000"
    When the player places a bet of "1000" in round "r1"
    Then the deposit pool is "5000" and the converted pool is "4000"
    And the bet's parts are deposit "0" and converted "1000"

  Scenario: a stake larger than the converted pool takes the remainder from the deposit pool
    Given a deposit pool of "5000" and a converted pool of "5000"
    When the player places a bet of "6000" in round "r1"
    Then the deposit pool is "4000" and the converted pool is "0"
    And the bet's parts are deposit "1000" and converted "5000"

  Scenario: a stake funded only from restricted funds moves neither pool
    Given a deposit pool of "5000" and a converted pool of "0"
    And an active restricted funds record of "2000"
    When the player places a bet of "1000" funded only from restricted funds
    Then the deposit pool is "5000" and the converted pool is "0"
    And the bet's parts are deposit "0" and converted "0"

  Scenario: a conversion joins the converted pool before the stake's real leg is split
    Given a deposit pool of "1000" and a converted pool of "0"
    And an active restricted funds record of "3000" with a wagering requirement of "20000" and release progress of "19500" at a contribution rate of "100"
    When the player places a bet of "1000" funded from real balance
    Then the record converts: its balance is credited to real balance
    And the converted pool received "3000" before the real leg was split
    And the bet's parts are deposit "0" and converted "1000"
    And the deposit pool is "1000" and the converted pool is "2000"

  Scenario: a win is split in the stake's ratio with the deposit part rounded down
    Given round "r1" is open with a bet whose parts are deposit "1000" and converted "5000"
    When a win of "1000" is credited for round "r1"
    Then the win's parts are deposit "166" and converted "834"
    And the deposit pool rises by "166" and the converted pool rises by "834"

  Scenario: a win on a stake with no real leg moves neither pool while its record is active
    Given round "r1" is open with a bet of "1000" funded only from an active restricted funds record
    When a win of "2000" is credited for round "r1"
    Then the record receives "2000"
    And the deposit pool and the converted pool are unchanged

  Scenario: a win's share for a record no longer active goes to the converted pool
    Given round "r1" is open with a bet of "1000" funded only from a restricted funds record that has since been forfeited
    When a win of "2000" is credited for round "r1"
    Then the win's parts are deposit "0" and converted "2000"
    And the converted pool rises by "2000"

  Scenario: a rollback restores the stake's recorded parts after later movements changed the pools
    Given round "r1" is open with a bet whose parts are deposit "1000" and converted "5000"
    And a deposit of "9000" under payment reference "p1" was confirmed since
    When the bet in round "r1" is rolled back
    Then the deposit pool rises by "1000" and the converted pool rises by "5000"
    And the rollback's parts are deposit "1000" and converted "5000"

  Scenario: settling a round as a loss moves neither pool
    Given round "r1" is open with a bet whose parts are deposit "1000" and converted "5000"
    When round "r1" is settled as a loss
    Then the deposit pool and the converted pool are unchanged

  Scenario: a correction debit that takes real balance negative charges the shortfall to the converted pool
    Given a deposit pool of "300" and a converted pool of "200"
    When a correction debit of "1000" is placed allowing a negative balance
    Then the real balance is "-500"
    And the deposit pool is "0" and the converted pool is "-500"
    And the debit's parts are deposit "300" and converted "700"

  Scenario: a deposit joins the deposit pool
    Given a deposit pool of "0" and a converted pool of "2000"
    When a deposit of "5000" under payment reference "p1" is confirmed
    Then the deposit pool is "5000" and the converted pool is "2000"

  Scenario: only the deposit part of a stake reduces deposit turnover pending
    Given a deposit pool of "5000" and a converted pool of "5000"
    And deposit turnover pending of "20000"
    When the player places a bet of "6000" in round "r1"
    Then the bet's parts are deposit "1000" and converted "5000"
    And deposit turnover pending is "19000"

  Scenario: a hold takes the paid amount from the deposit pool first
    Given a deposit pool of "5000" and a converted pool of "5000", no restricted funds and no deposit turnover pending
    When a hold of "6000" is placed for request "w1"
    Then the deposit pool is "0" and the converted pool is "4000"
    And the hold's paid parts are deposit "5000" and converted "1000"

  Scenario: a hold's cap void and clawback come from the converted pool first, before the paid amount
    Given a deposit pool of "6000" and a converted pool of "3000"
    And a restricted funds record awaiting its cap with deposits since conversion of "0" and a remaining allowance of "2000"
    And an active restricted funds record under withdrawal policy "funds_and_winnings" with a clawback of "1500" computed for it
    When a hold of "4000" is placed for request "w1"
    Then the clawback's parts are deposit "0" and converted "1500"
    And "2000" is voided as "bonus_cap_void" with parts deposit "500" and converted "1500"
    And the paid amount "2000" has parts deposit "2000" and converted "0"
    And the deposit pool is "3500" and the converted pool is "0"

  Scenario: a release returns the hold's parts to the pools they were taken from
    Given a hold of "6000" is pending for request "w1" with paid parts deposit "5000" and converted "1000"
    And a deposit pool of "0" and a converted pool of "4000"
    When request "w1" is released as cancelled
    Then the deposit pool is "5000" and the converted pool is "5000"

  Scenario: a reversal returns the hold's parts to the pools they were taken from
    Given request "w1" of "6000" was confirmed with paid parts deposit "5000" and converted "1000"
    When request "w1" is reversed
    Then the deposit pool rises by "5000" and the converted pool rises by "1000"

  Scenario: an operator's credit joins the deposit pool
    Given a deposit pool of "1000" and a converted pool of "1000"
    When an operator credits "500" to real balance with reason "goodwill"
    Then the deposit pool is "1500" and the converted pool is "1000"

  Scenario: an operator's debit comes from the deposit pool first
    Given a deposit pool of "1000" and a converted pool of "1000"
    When an operator debits "1500" from real balance with reason "correction"
    Then the deposit pool is "0" and the converted pool is "500"
    And the debit's parts are deposit "1000" and converted "500"

  Scenario: a provider credit to real balance joins the converted pool
    Given no active restricted funds record, a deposit pool of "1000" and a converted pool of "0"
    When a provider credit of "700" arrives
    Then the deposit pool is "1000" and the converted pool is "700"

  Scenario: cancelling an award takes its clawback from the converted pool first
    Given a deposit pool of "1000" and a converted pool of "1000"
    And an active restricted funds record whose clawback computed from its baseline is "1500"
    When the award is cancelled
    Then the clawback's parts are deposit "500" and converted "1000"
    And the deposit pool is "500" and the converted pool is "0"

  Scenario: the two pools always sum to the real balance
    Given a deposit pool of "5000" and a converted pool of "3000"
    When a bet of "4000" in round "r1", its win of "1000", a deposit of "2000" under payment reference "p1" and a hold of "3000" are applied in turn
    Then after each the deposit pool plus the converted pool equals the real balance

  Scenario: every fact whose movement touches real balance carries its parts and both pools after it
    Given a deposit pool of "2000" and a converted pool of "500"
    When the player places a bet of "1500" in round "r1"
    Then the "bet_placed" fact carries parts deposit "1000" and converted "500"
    And it carries the deposit pool after as "1000" and the converted pool after as "0"

  Scenario: the balance read reports both pools beside the real balance
    Given a deposit pool of "5000" and a converted pool of "3000"
    When the balance is read by the operator
    Then the deposit pool "5000" and the converted pool "3000" are returned beside the real balance "8000"

  Scenario: a wallet opened by the migration carries the original's pools
    Given the migration of brand "spinvibe" from the original
    And a player with a real balance of "8000" whose original wallet carries a deposit pool of "5000" and a converted pool of "3000"
    When the migration has run
    Then the deposit pool is "5000" and the converted pool is "3000"

  Scenario: a wallet opened by the migration from a wallet the original never stamped is all converted
    Given the migration of brand "spinvibe" from the original
    And a player with a real balance of "8000" whose original wallet carries no deposit pool and no converted pool
    When the migration has run
    Then the deposit pool is "0" and the converted pool is "8000"
