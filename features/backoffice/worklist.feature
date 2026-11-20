Feature: An operator's worklist
  An operator starts from the open work their roles let them act on in the current brand, oldest
  first, with each kind's count, rather than from a page per queue.

  Scenario: an operator's worklist holds the open items their roles may act on, oldest first
    Given a brand with a withdrawal request pending review, an identity document awaiting review and an open fraud case
    When a finance operator opens their worklist
    Then the worklist holds the withdrawal request and nothing else
    When a compliance operator opens their worklist
    Then the worklist holds the identity document and the fraud case, oldest first

  Scenario: acting on an item returns the operator to the worklist at the same place
    Given a finance operator at the tenth item of their worklist
    When the operator approves that withdrawal request
    Then the operator is back at the worklist at the same place
    And the withdrawal request is no longer in the worklist

  Scenario: an item another operator has just acted on shows who did what and offers no action
    Given two finance operators each with the same withdrawal request in their worklist
    When one operator approves it
    Then the other operator sees who approved it and when
    And the other operator is offered no action on it

  Scenario: a kind whose source cannot be read is shown as unavailable, not as empty
    Given a brand whose identity queue cannot be read
    When a compliance operator opens their worklist
    Then identity documents are shown as unavailable
    And they are not shown as none
