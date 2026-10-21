Feature: Players read the catalogue, prices and their positions
  A player sees open markets with each outcome's prices and depth, and their own orders,
  positions and trades. A draft or unopened market is not shown; another player's orders are
  not shown.

  Background:
    Given a brand running the module "predictions"
    And a player "ada" with an active account
    And a player "bob" with an active account

  Scenario: a player sees open markets with prices and depth and no draft or unopened market
    Given an open market "m1" with the outcomes "yes" and "no" in "EUR"
    And a draft market "m2" with the outcomes "yes" and "no" in "EUR"
    And an approved market "m3" that has not opened
    When "ada" reads the market catalogue
    Then "ada" sees market "m1" with the last price, best bid, best ask and depth of each outcome
    And "ada" does not see market "m2" or market "m3"

  Scenario: a player sees their own orders, positions and trades and no other player's
    Given an open market "m1" with the outcomes "yes" and "no" in "EUR"
    And "ada" has an order, a position and a trade in market "m1"
    And "bob" has an order, a position and a trade in market "m1"
    When "ada" reads her orders, positions and trades
    Then "ada" sees her own and nothing of "bob"
