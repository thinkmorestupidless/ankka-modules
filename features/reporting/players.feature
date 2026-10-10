Feature: Compliance reviews players and a support agent finds a transaction
  A compliance officer runs the player report for a registration period, filters by country and
  status, sorts by lifetime GGR, and opens a player. A support agent runs the transaction report
  for a period and a type, searches by a player's email address, and exports every matching row.

  Background:
    Given brand "goldvibe"

  Scenario: the player report lists one row per player registered in the period with exact lifetime figures
    Given players registered in a period
    When compliance runs the player report for it
    Then one row per player registered in the period shows email address, country, registration date, player status, KYC status, lifetime deposits, lifetime wagered, lifetime GGR, last sign-in and whether the account is a test account
    And each lifetime figure is exact over every transaction

  Scenario: the player report filtered or sorted narrows or orders the rows, and its export holds every matching row regardless of the page
    Given the player report
    When compliance filters it by country or player status, or sorts it by a column
    Then the rows are narrowed or ordered accordingly
    And the export holds every matching row regardless of the page shown

  Scenario: the transaction report shows every transaction of a player with many, newest first, paged at one hundred rows
    Given a player with more than "200" transactions in the period
    When the agent runs the transaction report
    Then every transaction appears, newest first, paged at "100" rows
    And each shows its time, the truncated email address, type, amount, currency and reference

  Scenario: the transaction report filtered by type or searched by part of an email address shows only matching rows, and its export holds all of them
    Given the transaction report
    When the agent filters it by type or searches by part of an email address
    Then only matching rows appear
    And the export holds every matching row across every page

  Scenario: an erased player's rows in the player report or the transaction report read the email address as erased with every figure unchanged
    Given an erased player
    When the player report or the transaction report includes their rows
    Then the email address reads as erased
    And every figure is unchanged
