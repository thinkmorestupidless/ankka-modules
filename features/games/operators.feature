Feature: Operators see what the house provider sent and what the brands answered
  An operator of the installation reads, per brand, the sessions, rounds and provider calls the
  house provider sent, with every attempt and answer. An unanswered call is listed with its retry
  state and can be abandoned with a reason. Finance exports a brand's settled rounds for a day.
  Every operator act on the house provider is an audit record.

  Background:
    Given a house provider serving a brand

  Scenario: an operator reads a brand's rounds and the callbacks of one round with their attempts and answers
    Given a round played at the brand
    When an operator reads the brand's rounds and opens that round
    Then each provider call of the round is listed with its content as sent, the signing key's id, the time sent, each attempt, the answer's word and balance and the time taken

  Scenario: a callback the brand has not acknowledged is listed with its attempts and next retry
    Given a credit the brand answered "error" on every attempt so far
    When an operator lists the brand's unanswered calls
    Then the credit is listed with its attempts and the time of its next retry

  Scenario: an operator abandons an unacknowledged callback with a reason and no further attempt is made
    Given an unanswered call
    When an operator abandons it with a reason
    Then no further attempt is made
    And the call is listed as abandoned with the reason and the operator

  Scenario: finance exports a brand's settled rounds for a day
    Given rounds settled at the brand on "2026-11-07" and on "2026-11-08"
    When finance exports the brand's settled rounds for "2026-11-07"
    Then the export holds every round settled that day with its stake, win, outcome and transaction ids, and none of the next day's

  Scenario: every operator act on the provider is a record on the shared audit
    Given an operator signed in to the installation
    When the operator writes a brand entry, starts a key rotation or abandons an unanswered call
    Then an audit record on the platform audit topic names the operator, the act and the time

  Scenario: records are swept after the installation's retention for the provider
    Given a round whose last provider call is older than the installation's retention for the house provider
    When the sweep runs
    Then the round, its session and its provider calls are gone
    And a younger round is untouched

  Scenario: after the retention a credit the brand never acknowledged is kept, and a session that sent the brand nothing is swept
    Given a round whose credit was exhausted and a session in fun mode, both older than the installation's retention for the house provider
    When the sweep runs
    Then the exhausted credit and its session are kept until an operator abandons it
    And the session in fun mode is gone
