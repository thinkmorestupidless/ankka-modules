Feature: An order passes the brand's gates and is funded before it reaches a book
  Before an order reaches a book, the exchange checks the player's account, the play gate and the
  bet gate, then reserves the order's cost in the player's wallet. An order any check refuses
  rests nowhere and reserves nothing, naming the gate and the casino's reason.

  Background:
    Given a brand running the module "predictions"
    And an open market "m1" with the outcomes "yes" and "no" in "EUR"
    And a player "ada" with an active account and a wallet in "EUR" with a real balance of "1000"
    And the play gate allows "ada"
    And the bet gate allows "ada"

  Scenario: a buy order is checked against the account, the play gate and the bet gate, then funded, then rests
    When "ada" places a buy order "o1" for "10" shares of "yes" in "m1" at price "60"
    Then the account of "ada", the play gate and the bet gate were asked, in that order
    And a reservation of "600" is placed for order "o1" on the wallet of "ada"
    And the order "o1" rests in the book of "yes" in "m1"

  Scenario: an order the play gate refuses rests nowhere and reserves nothing
    Given the play gate refuses "ada"
    When "ada" places a buy order "o2" for "10" shares of "yes" in "m1" at price "60"
    Then it is refused naming the play gate
    And nothing rests in the book of "yes" in "m1"
    And the reserved amount of "ada" is "0"

  Scenario: an order the bet gate refuses rests nowhere and names the casino's reason
    Given the bet gate refuses "ada" as "cooling off"
    When "ada" places a buy order "o3" for "10" shares of "yes" in "m1" at price "60"
    Then it is refused naming the bet gate and the reason "cooling off"
    And nothing rests in the book of "yes" in "m1"
    And the reserved amount of "ada" is "0"

  Scenario: an order the wallet cannot fund is refused as insufficient funds and rests nowhere
    When "ada" places a buy order "o4" for "20" shares of "yes" in "m1" at price "60"
    Then it is refused as "insufficient funds"
    And nothing rests in the book of "yes" in "m1"

  Scenario: a sell order needs the position and reserves nothing
    Given "ada" holds "10" shares of "yes" in "m1"
    When "ada" places a sell order "o5" for "10" shares of "yes" in "m1" at price "60"
    Then the order "o5" rests in the book of "yes" in "m1"
    And the reserved amount of "ada" is "0"
    When "ada" places a sell order "o6" for "5" shares of "no" in "m1" at price "60"
    Then it is refused naming the position
