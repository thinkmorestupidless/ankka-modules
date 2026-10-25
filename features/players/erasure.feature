Feature: A player's data is erased and the erasure response assembled
  A player, or an operator for them, asks for erasure. The brand files it in the brand and in the
  shared payments, with the hold the brand's retention rule names, under one correlation id; when
  every part has applied it, the brand tells the realm to remove the user and assembles the
  certificates into the erasure response. Before erasure, a player can be given an export of what
  the brand holds about them.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a filed erasure exists in the brand and in the shared payments under one correlation id with the brand's hold
    Given the player is closed
    When erasure is filed
    Then an erasure request exists in the brand and one in the shared payments with the same correlation id and the brand's retention hold
    And the player status shows "erasure_pending" with the hold's date

  Scenario: an erasure whose hold has lapsed and which every part has applied removes the realm user and assembles the certificates
    Given an erasure whose retention hold has lapsed
    When every part of the brand and the shared payments reports it applied
    Then the brand tells the realm to remove the user
    And the certificates are assembled into the erasure response
    And the player's personal fields read as erased everywhere
    And their money facts read unchanged

  Scenario: an erasure filed for a player with an open withdrawal is held until the withdrawal completes or fails
    Given the player has an open withdrawal request
    When erasure is filed
    Then it is accepted and held until the withdrawal completes or fails
    And the hold's reason says so

  Scenario: a player asking for their data receives an export with operator notes excluded
    Given the player has a profile, consents, sessions, activity and communications
    When they ask for their data
    Then they receive an export covering the profile, consents, sessions, activity history and communications
    And operator notes are excluded

  Scenario: a registration with an erased player's email address creates a new player with a new id
    Given an erased player's email address
    When a visitor registers with it
    Then a new player with a new id is created
    And the realm accepts the address

  Scenario: a player's export carries everything the brand holds, each service's part named
    Given a player with sessions, deposits, bets and a reviewed document
    When the player asks for their export
    Then it carries their profile, consents, sessions, activity and inbox items
    And the wallet's transactions, the payments and the document records, not the documents
    And no operator note

  Scenario: a service that does not answer the export is named as missing, not left out
    Given the wallet does not answer in time
    When the player asks for their export
    Then the export names the wallet's part as missing
