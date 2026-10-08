Feature: The original is recorded and the rebuild held to it
  Before a part is rebuilt, the original is run locally with every outside system stubbed, its own
  end-to-end suite is run against it, and what it answered is recorded. Each rebuilt part replays
  the recordings its spec names and must answer as the original did.

  Scenario: running the original's end-to-end suite with every provider stubbed leaves one recording per scenario and nothing leaves the machine
    Given the original running locally with the game provider and every payment provider stubbed
    When its end-to-end suite runs
    Then one recording exists per scenario, kept in this repository
    And no request left the machine

  Scenario: recording refuses to start while a payment provider the original calls has no stub
    Given a payment provider the original calls that has no stub
    When the recording is started
    Then it is refused, naming the provider

  Scenario: every requirement in a spec names what established it
    Given a spec
    When it is written
    Then every requirement it carries names the recording, the original's source or the decision that established it

  Scenario: a rebuilt part replays every recording its spec names and a changed answer fails naming the recording and the field
    Given a rebuilt part and the recordings its spec names
    When its parity tests run
    Then every recording replays
    And a changed answer fails the parity test, naming the recording and the field

  Scenario: the original's frontend suite passes against a rebuilt brand with only its addresses changed
    Given a frontend of the original
    When it is pointed at a rebuilt brand
    Then the original's frontend suite passes with only its addresses changed
