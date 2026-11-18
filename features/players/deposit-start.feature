Feature: A player starts a deposit at the brand
  A player asks the brand to start a deposit of an amount in a currency. The brand asks the deposit
  gate, reads the wallet's sums and asks responsible gambling, and only then opens a cashier session
  at the shared payments under the brand's cashier key with the player's own id and profile. The
  player is answered what the cashier session offers, so that their page can finish the deposit.

  Background:
    Given a player of brand "guyanabet" who is active

  Scenario: a started deposit answers the cashier ticket and the terminals of the cashier session it opened
    Given the deposit gate and responsible gambling allow a deposit of "5000" minor units of "EUR"
    When the player starts a deposit of "5000" minor units of "EUR"
    Then a cashier session is opened at the shared payments for the player
    And the player is answered the cashier ticket and the terminals the cashier session offers

  Scenario: a deposit the deposit gate refuses opens no cashier session and answers no cashier ticket
    Given the deposit gate refuses the player
    When the player starts a deposit of "5000" minor units of "EUR"
    Then the player is refused with the deposit gate's code and words
    And no cashier session is opened
