Feature: The cutover is rehearsed until it is boring
  Before the real cutover window, the team runs the whole runbook against a production snapshot in
  a rehearsal installation, twice, and the second run's timings set the window.

  Scenario: a rehearsal run end to end records every step's elapsed time and lists every finding
    Given a production snapshot restored into the rehearsal installation
    When the runbook is run end to end
    Then every step's elapsed time is recorded
    And the report lists every finding

  Scenario: a real cutover window proposed while a rehearsal has an open finding is refused until a later rehearsal closes it
    Given a rehearsal with an open finding
    When the real cutover window is proposed
    Then it is refused until a later rehearsal closes the finding

  Scenario: production personal data in the rehearsal installation is removed when the rehearsal ends, and the removal recorded
    Given production personal data in the rehearsal installation
    When the rehearsal ends
    Then the personal data is removed
    And the removal is recorded
