Feature: Documents are kept as long as the regulator needs and then erased
  A player's documents live in the brand's document store under the player's prefix, encrypted at
  rest, readable only through addresses the brand issues. When the player is erased under a hold
  that has lapsed, every version of every document goes, with the personal fields about them.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a document is kept under the player's prefix in the brand's document store, encrypted at rest
    Given a document of the player
    When its object is read in the document store
    Then it is under the player's own prefix in the brand's document store
    And it is encrypted at rest by the store

  Scenario: an erasure applied after its hold lapses deletes every version of every document and keeps the ids, times, categories, statuses and reason codes readable
    Given an erasure request for the player under a retention hold
    When the hold lapses and the erasure is applied
    Then every object under the player's prefix is deleted in every version
    And the document ids, times, categories, statuses and reason codes stay readable
    And the erasure is final when the store's recovery period ends

  Scenario: an erased player's documents are listed with their statuses and no address can be issued
    Given the player is erased
    When an operator opens their KYC tab
    Then the documents are listed with their statuses
    And no view address can be issued

  Scenario: a document written for an erased player after the erasure is removed when the erasure handler runs again
    Given a document written for the player after their erasure
    When the erasure handler runs again
    Then the object is removed
