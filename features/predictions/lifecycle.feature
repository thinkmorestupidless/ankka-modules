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
