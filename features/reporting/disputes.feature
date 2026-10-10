Feature: A support agent generates a dispute report
  A player disputes a win. A support agent generates a dispute report for one player session, or
  for a date range, and receives a document with a reference number to quote: the player sessions
  with their start, end, close reason, device and address, each game session with its rounds,
  stakes, wins, net result and status, and the period's totals.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a dispute report for a player session holds the sessions, every game session and round, the totals and a reference number
    Given a player session of the player with game sessions and rounds
    When the agent generates the dispute report for it
    Then the document holds the brand, the player's email address, the period, and the player session's start, end, close reason, network address and user agent
    And each game session's game, type, start and end
    And each round's number, time, stake, win, net result and status
    And the period totals, the generation time and a unique reference number

  Scenario: a dispute report for a date range of up to a year includes every player session in the range
    Given a date range of up to a year
    When the agent generates the dispute report for it
    Then every player session of the player in the range is included

  Scenario Outline: a dispute report asked for a range the rules do not allow is refused naming the rule
    Given <request>
    When the agent generates the dispute report for it
    Then it is refused naming the rule

    Examples:
      | request                                   |
      | a date range over a year                  |
      | a date range with its end before its start |
      | a player session and a date range together |

  Scenario Outline: a dispute report is served as an attachment under a file name carrying the reference number
    Given a dispute report asked for as "<format>"
    When it is generated
    Then it is served as an attachment under a file name carrying the reference number

    Examples:
      | format |
      | HTML   |
      | CSV    |
      | PDF    |

  Scenario: a dispute report whose rounds exceed the synchronous limit becomes a job whose progress the agent reads and whose document is served once ready
    Given a date range whose rounds exceed the synchronous limit
    When the agent asks for the dispute report
    Then a job and its reference number are returned
    And the agent can read its progress
    And the document is served once ready

  Scenario: a generated document is no longer served after a day and the record of the report remains
    Given a generated dispute report document
    When "24" hours have passed
    Then the document is no longer served
    And the record of the report, its reference number, who asked, the period and when, remains

  Scenario: a round the wallet settled but the game provider's record lacks is included from the wallet's facts and marked unmatched
    Given a round the wallet settled that the game provider's record lacks
    When the dispute report is generated
    Then the round is included from the wallet's facts
    And it is marked as unmatched
