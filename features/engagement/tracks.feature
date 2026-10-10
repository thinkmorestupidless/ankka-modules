Feature: A fact becomes one CRM track, exactly once
  A player deposits. The wallet's fact arrives; the brand's engagement refreshes the player's
  profile view, identifies the player in the CRM, tracks the deposit, and sends the transactional
  send the brand configured for the trigger. The same fact arriving again finds the send record and
  sends nothing. A fact older than the staleness bound at first sight is recorded as stale and
  never sent.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a deposit confirmed fact makes one identify, one track and one transactional send, and a send record names them
    Given the brand has a CRM configured, with a transactional id for the deposit trigger
    When a "deposit_confirmed" fact arrives
    Then one identify, one "deposit_confirmed" track and one transactional send are made
    And a send record names the fact id, the track, the time and the outcome

  Scenario: the same fact delivered again makes no call and leaves the send record unchanged
    Given a "deposit_confirmed" fact already consumed, with its send record
    When the same fact is delivered again
    Then no call is made to the CRM
    And the send record is unchanged

  Scenario: a deposit that makes the lifetime count one is tracked as a first deposit beside the deposit
    Given the player's lifetime deposit count is "0"
    When a "deposit_confirmed" fact arrives
    Then a "first_deposit_confirmed" track is made beside the "deposit_confirmed" track, with the same data

  Scenario: a fact older than the staleness bound at first sight is skipped as stale
    Given a fact older than the brand's staleness bound at first sight
    When it is consumed
    Then nothing is sent
    And the send record says "skipped_stale"

  Scenario: a brand with no CRM configured sends nothing and names the gap on its status
    Given the brand has no CRM configured
    When any fact arrives
    Then nothing is sent
    And the send record says "no_configuration"
    And the brand's status names the gap

  Scenario: a retryable failure from the CRM is retried with backoff and then becomes a parked send
    Given the CRM answers a retryable failure
    When a send is attempted
    Then it is retried with backoff, four attempts in all
    And then it is a parked send with the last answer
