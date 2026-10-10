Feature: Every simulator run is observable and can become a parity test
  A tester opens the simulator run of a session and sees every provider call sent, the answer, the timing
  and the scenario step that caused it; a simulator run exports as a recording in the shape the recordings
  are kept in, so a simulator run can become a parity test of the brand.

  Background:
    Given a brand configured with the simulator as its game provider

  Scenario: a simulator run lists each provider call with what was sent, the key it was signed with, the times, the answer, the attempt and the scenario step
    Given a session that has played a round
    When the tester opens its simulator run
    Then each provider call is listed with its content as sent, the signing key's id, the time sent, the answer's word and balance, the time taken, the attempt number and the simulator scenario step

  Scenario: an exported simulator run is a recording in the shape the recordings are kept in, and the brand's parity tests replay it
    Given a simulator run
    When the tester exports it
    Then the export is a recording in the shape the recordings are kept in
    And the brand's parity tests replay it

  Scenario: a simulator run of a simulator scenario shows each step's expected against observed and passed or failed with the first failing step
    Given a simulator run of a simulator scenario with a verdict
    When the tester reads the simulator run
    Then each step shows expected against observed
    And the simulator run shows passed or failed with the first failing step

  Scenario: simulator runs older than the installation's retention for the simulator are removed by the sweep and counted on the simulator's status
    Given simulator runs older than the installation's retention for the simulator
    When the sweep runs
    Then they are removed
    And the count is in the simulator's status
