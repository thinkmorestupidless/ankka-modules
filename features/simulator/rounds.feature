Feature: A brand plays a round against the simulator exactly as against the original's stub
  A tester launches a game at a brand, the simulator issues the game page, checks the launch
  ticket, and on the tester's instruction sends a bet, a win, a loss or a cancel to the brand,
  signed, and answers with what the brand answered. Nothing the brand sees differs from a simulator run
  against the original's stub.

  Background:
    Given a brand configured with the simulator as its game provider

  Scenario: a launch against the simulator stores the session, returns a game page address, and checks the launch ticket on the page's load
    Given a player of the brand
    When the player launches a game
    Then the simulator stores the session and returns a game page address
    And on the page's load it checks the launch ticket with the brand and keeps the session ticket the brand returns

  Scenario: a bet instructed by the tester is sent as one signed debit with a new transaction id and the brand's answer is returned unchanged
    Given an open session at the simulator
    When the tester instructs a bet of "1.50" in "EUR"
    Then the simulator sends one signed debit carrying a new transaction id, a round id, the currency, the amount and the kind "debit"
    And it returns the brand's answer and balance unchanged

  Scenario: a win instructed by the tester is sent as one signed credit for the round and the brand's answer is returned
    Given a bet in an open round at the simulator
    When the tester instructs a win of "3.00"
    Then the simulator sends one signed credit for the round
    And it returns the brand's answer

  Scenario: a loss instructed by the tester is sent as a credit of zero, which the brand settles as a loss
    Given a bet in an open round at the simulator
    When the tester instructs a loss
    Then the simulator sends a credit of "0.00" for the round
    And the brand settles the round as a loss

  Scenario: a cancel instructed by the tester is sent as one signed cancel naming the bet's transaction id and a new cancel id
    Given a bet in an open round at the simulator
    When the tester instructs a cancel
    Then the simulator sends one signed cancel naming the bet's transaction id and a new cancel id

  Scenario: the original's recordings replay against the rebuilt brand with the simulator in its default simulator scenario, with the same calls in the same order and the same answers
    Given the original's recordings made against its stub
    When the same suite runs against the rebuilt brand with the simulator in its default simulator scenario
    Then every recording replays with the same provider calls in the same order and the same answers
