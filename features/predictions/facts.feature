Feature: Facts, the price stream and the reasoning mirror
  Every order, trade and price change is a fact on the brand's exchange topics. When the brand's
  values name a reasoning service, markets are mirrored and prices observed; otherwise nothing is
  mirrored and nothing fails.

  Background:
    Given a brand running the module "predictions"
    And an open market "m1" with the outcomes "yes" and "no" in "EUR"
    And a player "ada" with an active account and a wallet in "EUR" with a real balance of "1000"
    And the play gate and the bet gate allow "ada"

  Scenario: every order, trade and price change is a fact on the brand's exchange topics
    When "ada" places a buy order "o1" for "10" shares of "yes" in "m1" at price "60"
    Then a fact for order "o1" is written on the brand's exchange orders topic, keyed by the player
    When order "o1" is matched by a trade "t1"
    Then a fact for trade "t1" is written on the brand's exchange trades topic, keyed by the market
    And a fact for the new last price of "yes" is written on the brand's exchange prices topic, keyed by the market

  Scenario: a brand with a reasoning service named mirrors each approved market and writes price observations at most once a minute per outcome
    Given the brand's values name a reasoning service
    When an operator approves a market "m2" with the outcomes "yes" and "no"
    Then market "m2" is mirrored to the reasoning service as a question with one hypothesis per outcome
    When the last price of "yes" in "m1" changes "five" times within one minute
    Then one price observation for "yes" in "m1" is written to the reasoning service

  Scenario: a brand with no reasoning service named mirrors nothing and trades
    Given the brand's values name no reasoning service
    When an operator approves a market "m2" with the outcomes "yes" and "no"
    And "ada" places a buy order "o1" for "10" shares of "yes" in "m1" at price "60"
    Then nothing is mirrored
    And the order "o1" rests in the book of "yes" in "m1"
