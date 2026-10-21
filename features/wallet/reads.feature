Feature: Reads of a wallet: balance, restricted funds status, transactions and sums
  The player, the operator and the brand's other parts read the wallet.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: a balance read returns real balance, restricted balance, currency and the primary record's progress
    Given an active restricted funds record
    When the balance is read
    Then real balance, restricted balance and currency are returned as major-unit strings
    And the primary record's status, wagering requirement, wagered amount and release progress are returned

  Scenario: a balance read reports the reserved and the available amount beside real balance
    Given a real balance of "1000" and a reservation of "600" placed for order "o1"
    When the balance is read
    Then real balance "1000", reserved amount "600" and available amount "400" are returned as major-unit strings

  Scenario: a restricted funds status read lists every open record in order with the primary record's view as the headline
    Given open restricted funds records
    When the restricted funds status is read
    Then every open record is listed in order with its balance, wagering requirement, release progress, cap fields, priority and withdrawal policy
    And the primary record's view is the headline

  Scenario: transactions are listed by type, wallet type, date range and page with every field of the row
    Given transactions
    When they are listed by type, wallet type, date range and page
    Then each row carries type, wallet type, amount, balance before and after, currency, status, payment reference, round, award reference, reason, terminal, payment provider and created time

  Scenario: a sum by type over a date range returns the total and the count, and lifetime totals report deposited, wagered and won
    Given transactions
    When a sum by type over a date range is asked
    Then the total and the count are returned
    And lifetime totals report deposited, wagered and won
