Feature: The game provider cancels a debit or a credit
  The game provider cancels a transaction under a cancel id: a cancelled debit is rolled back on the
  wallet; a cancelled credit is debited back, even below zero. A cancel for a transaction the brand
  never saw is answered transaction not found with the balance.

  Background:
    Given a player with a session ticket for a wallet in "EUR"

  Scenario: cancelling a debit in an open round rolls the bet back, restores the balance and closes the round as cancelled
    Given an open bet "t1" of "5.00" in round "r1"
    When the game provider cancels "t1" under cancel id "c1"
    Then the bet is rolled back
    And it is answered "ok" with the balance restored
    And round "r1" is closed as cancelled, with a "round_settled" fact of outcome "cancelled"

  Scenario: cancelling a credit debits its amount back as a bet keyed by the cancel id, allowed below zero
    Given a credit "t2" of "12.00" in round "r1"
    When the game provider cancels "t2" under cancel id "c2"
    Then "12.00" is debited as a bet keyed "c2" allowed to take the balance below zero
    And it is answered "ok"

  Scenario: cancelling a second debit of a round whose first was already rolled back rolls it back on its own key
    Given round "r1" whose debit "t1" was already rolled back and whose debit "t3" is open
    When the game provider cancels "t3" under cancel id "c3"
    Then "t3" is rolled back on its own key
    And it is answered "ok"

  Scenario: cancelling a debit the wallet no longer holds open refunds its amount as a provider credit keyed by the cancel id
    Given round "r1" whose bet the wallet no longer holds open
    When the game provider cancels its debit under cancel id "c4"
    Then the debit's amount is refunded as a provider credit keyed "c4"
    And it is answered "ok"

  Scenario: a cancel naming a transaction the brand never recorded is answered transaction not found with the current balance
    Given no transaction "t9" recorded for the player
    When the game provider cancels "t9" under cancel id "c9"
    Then it is answered "transaction_not_found" with the current balance

  Scenario: a cancel with no session ticket naming only the player and the transaction is served by player id
    Given an open bet "t1" of "5.00" in round "r1"
    When the game provider cancels "t1" naming only the player and the transaction, with no session ticket
    Then the player is resolved by id
    And the cancel is served

  Scenario: cancelling a round's credit and then its debit leaves the balance as it was before the round
    Given round "r1" with a debit "t1" and a credit "t2"
    When the game provider cancels "t2" and then "t1"
    Then the balance is what it was before round "r1"
