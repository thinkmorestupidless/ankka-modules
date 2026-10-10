Feature: The game provider debits a stake, credits a win or closes a round as a loss
  During play the game provider sends a debit for each stake, then a credit for the win or a zero
  credit for a loss, each under a transaction id and a round id. The brand places the bet with the
  wallet keyed by the transaction id, settles every open bet of the round on the credit, publishes
  the settled round as a fact, and answers the balance each time.

  Background:
    Given a player with a session ticket for a wallet in "EUR"

  Scenario: a debit places an open bet keyed by the transaction id and answers the balance
    Given the wallet has a real balance of "100.00"
    When the game provider debits "5.00" under transaction id "t1" in round "r1"
    Then it is answered "ok" with balance "95.00"
    And the wallet holds an open bet keyed "t1"

  Scenario: a credit settles the round's bet with the win, closes the round and publishes a round settled fact
    Given an open bet "t1" of "5.00" in round "r1" and a real balance of "95.00"
    When the game provider credits "12.00" under transaction id "t2" in round "r1"
    Then it is answered "ok" with balance "107.00"
    And bet "t1" is settled with the win
    And round "r1" is closed
    And a "round_settled" fact is published

  Scenario: a zero credit settles the bet as a loss and publishes a round settled fact with outcome loss
    Given an open bet "t1" of "5.00" in round "r1"
    When the game provider credits "0" in round "r1"
    Then it is answered "ok" with the balance unchanged
    And bet "t1" is settled as a loss
    And a "round_settled" fact with outcome "loss" is published

  Scenario: a debit the wallet cannot fund is answered not enough money with the balance and opens no bet
    Given the wallet has a real balance of "3.00"
    When the game provider debits "5.00" under transaction id "t1" in round "r1"
    Then it is answered "not_enough_money" with balance "3.00"
    And no bet is opened

  Scenario: a fresh spin at a zero balance is declined
    Given the wallet has a real balance of "0.00"
    When the game provider debits "0.50" under transaction id "t1" in round "r1"
    Then it is answered "not_enough_money"

  Scenario: a credit for a round with no debit is a provider credit keyed by the transaction id
    Given no debit in round "r2"
    When the game provider credits "7.00" under transaction id "t3" in round "r2"
    Then it is answered "ok"
    And the amount is a provider credit on the wallet keyed "t3"
    And a "round_settled" fact with no stake is published

  Scenario: a credit to a round with two debits settles the first with the win and the second as a loss
    Given open bets "t1" and "t2" in round "r1"
    When the game provider credits "20.00" in round "r1"
    Then the win settles bet "t1"
    And bet "t2" is settled as a loss
    And round "r1" is closed with both bets

  Scenario: a credit to a round already settled under a new transaction id is a provider credit
    Given round "r1" already settled
    When the game provider credits "4.00" under a new transaction id "t4" in round "r1"
    Then the amount is a provider credit keyed "t4"
    And it is answered "ok"

  Scenario Outline: a zero debit or a free debit places no bet, answers the current balance and records the round with a zero stake
    Given the wallet has a real balance of "100.00"
    When the game provider sends <debit> in round "r5"
    Then no bet is placed on the wallet
    And the current balance is answered
    And round "r5" is recorded with a stake of zero

    Examples:
      | debit                        |
      | a debit of "0"               |
      | a debit of kind "free_debit" |

  Scenario: a free debit under a session ticket carrying free rounds marks the round with them before the answer
    Given the session ticket carries free rounds
    When the game provider sends a debit of kind "free_debit" in round "r5"
    Then round "r5" is marked with the free rounds before the answer

  Scenario: a credit to a round marked with free rounds credits the wallet nothing and publishes the win for the reward rules to settle
    Given round "r5" is marked with free rounds
    When the game provider credits "3.00" in round "r5"
    Then the wallet is not credited
    And the current balance is answered
    And a "round_settled" fact carrying the free rounds and the win is published, for the winnings to be settled under the reward rules

  Scenario: a correction debit larger than the balance is placed as a bet allowed below zero
    Given the wallet has a real balance of "2.00"
    When the game provider debits "10.00" of kind "correction_debit" under transaction id "t6"
    Then it is placed as a bet allowed to take the balance below zero
    And it is answered "ok" with balance "-8.00"

  Scenario: a debit naming another game than the session ticket was issued for is served the same
    Given the session ticket was issued for game "slot-x"
    When the game provider debits "1.00" naming game "slot-y"
    Then it is served as any debit is

  Scenario: fields the brand does not read are ignored
    Given a provider call carrying fields the brand does not read
    When it arrives
    Then the fields the brand does not read are ignored

  Scenario Outline: a debit for a player cooling off or self-excluded is answered player locked and places nothing
    Given the player is <state>
    When the game provider debits "1.00" under transaction id "t7" in round "r7"
    Then it is answered "player_locked"
    And nothing is placed

    Examples:
      | state         |
      | cooling off   |
      | self-excluded |

  Scenario: a debit the wallet refuses for a responsible gambling limit is answered spending limit with the current balance
    Given the player's wallet refuses a bet for a responsible gambling limit
    When the game provider debits "1.00" under transaction id "t8" in round "r8"
    Then it is answered "spending_limit" with the current balance
