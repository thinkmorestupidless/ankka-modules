Feature: A trade settles between two players in one keyed command
  A settlement moves a trade's cost from the buyer's reservation to the seller's real balance,
  keyed by the trade, across two wallets. What the trade did not use is released to the buyer.
  A repeat moves nothing; an interrupted settlement is completed.

  Background:
    Given a player "ada" with a wallet in "EUR" and a real balance of "1000"
    And a player "bob" with a wallet in "EUR" and a real balance of "0"
    And a reservation of "650" is placed for order "o1" on the wallet of "ada"

  Scenario: a settlement moves the trade's cost from the buyer's reservation to the seller's real balance
    When trade "t1" of "600" is settled from order "o1" of "ada" to "bob"
    Then the real balance of "ada" is "400"
    And the reserved amount of "ada" is "50"
    And the real balance of "bob" is "600"
    And a settlement transaction of "600" is written on each wallet

  Scenario: a settlement at a better price than the order releases the difference to the buyer
    When trade "t1" of "600" is settled from order "o1" of "ada" to "bob" as the last trade of the order
    Then the reserved amount of "ada" is "0"
    And the available amount of "ada" is "400"

  Scenario: a settlement repeated under the trade's key returns the first outcome and moves nothing
    Given trade "t1" of "600" is settled from order "o1" of "ada" to "bob"
    When trade "t1" of "600" is settled from order "o1" of "ada" to "bob" again
    Then the outcome is the first outcome
    And the real balance of "bob" is "600"

  Scenario: a settlement interrupted between its two wallets is completed on restart
    Given trade "t1" of "600" is settled from order "o1" of "ada" to "bob" and the service stops after the debit
    When the service restarts
    Then the real balance of "bob" is "600"
    And the reserved amount of "ada" is "50"
