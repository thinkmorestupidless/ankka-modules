Feature: Correcting a player's money
  An operator corrects a balance with an adjustment that says why and what it answers; an incident
  affecting many players is corrected in one adjustment batch, one adjustment per player, never twice.

  Scenario: an adjustment shows the balance before and after and records its reason and cause
    Given a player with a real balance of "20.00 EUR" and an unsettled round from a provider's failure
    When a finance operator credits "5.00 EUR" with the reason "compensation" and the round as its cause
    Then the operator was shown "20.00 EUR" before and "25.00 EUR" after
    And the adjustment records the reason, the round and the operator

  Scenario: a debit larger than the balance is refused, not reduced
    Given a player with a real balance of "3.00 EUR"
    When a finance operator debits "5.00 EUR"
    Then the adjustment is refused because it exceeds the balance
    And the balance is still "3.00 EUR"

  Scenario: an adjustment to many players lists each as made, refused or already made, and a repeat makes none twice
    Given an incident affecting five players, one of them closed
    When a finance operator makes an adjustment batch for the five with one reason and cause
    Then four are listed as made and one as refused with its reason
    When the operator repeats the adjustment batch
    Then four are listed as already made and none is made twice
