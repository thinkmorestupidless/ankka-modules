Feature: Trades and resolutions move money through the brand's wallet
  A trade is settled through the wallet keyed by the trade, at least once. A final market pays
  each position from the market's pool account. A settlement the wallet refuses after the book
  has traded is recorded, retried and raised to an operator.

  Background:
    Given a brand running the module "predictions"
    And an open market "m1" with the outcomes "yes" and "no" in "EUR"
    And a player "ada" with an active account and a wallet in "EUR" with a real balance of "1000"
    And a player "bob" with an active account and a wallet in "EUR" with a real balance of "1000"
    And the play gate and the bet gate allow "ada" and "bob"

  Scenario: a trade is settled through the wallet keyed by the trade, once
    Given "bob" holds "10" shares of "yes" in "m1"
    And "bob" places a sell order "o1" for "10" shares of "yes" in "m1" at price "60"
    When "ada" places a buy order "o2" for "10" shares of "yes" in "m1" at price "60"
    Then trade "t1" is settled through the wallet from order "o2" of "ada" to "bob" for "600"
    And the settlement of trade "t1" is sent once however many times the exchange retries

  Scenario: a final market pays each position from the market's pool account
    Given "ada" holds "10" shares of "yes" in "m1"
    And "bob" holds "10" shares of "no" in "m1"
    And the pool account of market "m1" in "EUR" holds "1000"
    When market "m1" is final with the outcome "yes"
    Then a resolution payment of "1000" is paid to "ada" from the pool account of market "m1"
    And nothing is paid to "bob"
    And the pool account of market "m1" in "EUR" holds "0"

  Scenario: a settlement the wallet refuses is recorded, retried and raised to an operator
    Given the wallet refuses the settlement of trade "t1"
    When trade "t1" is sent for settlement
    Then the refusal is recorded against trade "t1"
    And the settlement is retried
    And a record naming trade "t1" is written on the brand's platform audit topic
