Feature: A simulator scenario makes the game provider misbehave on purpose
  A tester selects a simulator scenario for a session or a brand, and the simulator does what a
  real provider does when things go wrong: sends a debit again after a slow answer, cancels after
  a credit, sends a round's calls out of order, storms the brand with retries, signs with a rotated
  key, names a session ticket the brand never issued. The scenario states what the brand must
  answer and the simulator run records whether it did.

  Background:
    Given a brand configured with the simulator as its game provider

  Scenario: a debit answered slowly is sent again under the same transaction id, and the simulator run passes only if the second answer equals the first and the balance moved once
    Given the simulator scenario "duplicate debit after a slow answer"
    When the brand's answer to a debit takes longer than the scenario's limit
    Then the simulator sends the same debit again with the same transaction id
    And the simulator run passes only if the brand's second answer equals its first and the player's balance moved once

  Scenario: a cancel sent after a credit passes only if the brand answers as the original does for a cancel of a settled round
    Given the simulator scenario "cancel after credit"
    When a credit has been answered "ok"
    Then the simulator sends a cancel of the bet
    And the simulator run passes only if the brand answers as the original does for a cancel of a settled round and the balance is what the original would leave

  Scenario: a credit sent before its debit passes only if the brand answers as for an unknown round and later accepts the debit
    Given the simulator scenario "out of order"
    When a round's credit is sent before its debit
    Then the simulator run passes only if the brand answers the credit as the original answers a credit for an unknown round, and later accepts the debit

  Scenario: a retry storm resends on the provider's schedule until the brand stops answering error or the cap is reached, and reports the count and time
    Given the simulator scenario "retry storm"
    When the brand answers "error"
    Then the simulator resends on the provider's schedule until the brand answers a word that is not "error" or the scenario's cap is reached
    And the simulator run reports the count and the time to settle

  Scenario: a rotated key passes only if the brand admits calls under the next key within its bound and refuses calls under a key no longer listed
    Given the simulator scenario "rotated key"
    When the simulator signs with its next key after publishing it in its key document
    Then the simulator run passes only if the brand admits provider calls under the next key within the brand's configured bound
    And the brand refuses provider calls under a key the key document no longer lists

  Scenario: a debit naming a session ticket the brand never issued passes only if the brand answers session expired and no balance moves
    Given the simulator scenario "unknown session ticket"
    When a debit names a session ticket the brand never issued
    Then the simulator run passes only if the brand answers "session_expired" and no balance moves

  Scenario: a win far larger than any bet passes only if the brand answers exactly as the original does, with the balances before and after recorded
    Given the simulator scenario "win larger than any bet"
    When a credit of "1000000.00" follows a bet of "0.10"
    Then the simulator run passes only if the brand answers exactly as the original does
    And the simulator run records the balance before and after

  Scenario: an orphaned bet is settled by a credit of zero after the scenario's window, and the simulator run passes only if the brand settles the round as a loss
    Given the simulator scenario "orphaned bet"
    When a debit is answered "ok" and no credit, cancel or loss follows within the scenario's window
    Then the simulator sends a credit of "0.00"
    And the simulator run passes only if the brand settles the round as a loss

  Scenario: a brand answering slower than the provider waits has the request abandoned and retried as the provider would, with every attempt and answer recorded
    Given the simulator scenario "slow operator"
    When the brand takes longer to answer than the provider waits
    Then the simulator abandons the request and retries as the provider would
    And the simulator run records every attempt and every answer

  Scenario: a simulator scenario selected for one session leaves another session on the same brand on the default scenario
    Given a simulator scenario selected for one session
    When another session on the same brand plays
    Then the other session runs the default simulator scenario
