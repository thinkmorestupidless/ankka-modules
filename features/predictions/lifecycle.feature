Feature: Operators run a market's lifecycle under their brand's roles
  An operator with the brand's markets permission drafts, approves, closes, resolves and voids a
  market. Every other caller is refused naming the permission. Every change is a fact on the
  brand's markets topic.

  Background:
    Given a brand running the module "predictions"

  Scenario: an operator with the markets permission drafts, approves, closes, resolves and voids a market
    Given an operator of the brand with the markets permission
    When the operator drafts a market "m1" with the outcomes "yes" and "no"
    And the operator approves market "m1"
    And the operator closes market "m1"
    And the operator resolves market "m1" with the outcome "yes"
    Then market "m1" is resolved with the outcome "yes"
    When the operator drafts a market "m2" with the outcomes "yes" and "no"
    And the operator approves market "m2"
    And the operator voids market "m2"
    Then market "m2" is void

  Scenario: an operator without the markets permission is refused naming the permission
    Given an operator of the brand without the markets permission
    When the operator drafts a market "m3" with the outcomes "yes" and "no"
    Then it is refused naming the markets permission

  Scenario: every change to a market is a fact on the brand's markets topic
    Given an operator of the brand with the markets permission
    When the operator drafts a market "m4" with the outcomes "yes" and "no"
    And the operator approves market "m4"
    Then a fact for each change to market "m4" is written on the brand's markets topic, keyed by the market

  Scenario: a draft in a category the brand does not offer is refused naming the category
    Given an operator of the brand with the markets permission
    And the brand offers the categories "sport" and "politics"
    When the operator drafts a market "m5" in the category "weather"
    Then it is refused naming the category

  Scenario: a draft in a currency the brand's wallet does not hold is refused naming the currency
    Given an operator of the brand with the markets permission
    And the brand's wallet holds "EUR" only
    When the operator drafts a market "m6" in "USD"
    Then it is refused naming the currency

  Scenario: a market opens with the brand's limits unless its draft sets its own
    Given an operator of the brand with the markets permission
    And the brand's limits are a maximum order cost of "100000" and a maximum position of "5000"
    When the operator drafts a market "m7" and approves it
    And the operator drafts a market "m8" with a maximum position of "500" and approves it
    Then the book of market "m7" opens with a maximum order cost of "100000" and a maximum position of "5000"
    And the book of market "m8" opens with a maximum order cost of "100000" and a maximum position of "500"
