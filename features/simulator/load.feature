Feature: A load profile plays many sessions at a rate
  A tester starts a load profile of many sessions playing rounds a minute for a duration against a
  brand, and reads the throughput, the answer-time distribution and every answer that was not ok.

  Background:
    Given a brand configured with the simulator as its game provider

  Scenario: a load profile launches its sessions, plays rounds at the rate, and reports rounds sent, answers by word and the percentile answer times
    Given a load profile of "100" sessions at "10" rounds a minute for "5" minutes
    When it runs
    Then "100" sessions are launched, each playing rounds at the rate with the simulator scenario's outcomes
    And the report shows rounds sent, answers by word, and the "50"th, "95"th and "99"th percentile answer times

  Scenario: a load profile stopped by the tester settles its open rounds as losses before it ends
    Given a running load profile
    When the tester stops it
    Then open rounds are settled as losses before the profile ends
