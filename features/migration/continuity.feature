Feature: The affiliates and the readers see continuity across the cutover
  An affiliate who sent Spinvibe players last year signs in to the programme and sees the same
  revenue, the same players and the same payouts, and their existing legacy tag links still
  attribute. The analytics reader keeps reading.

  Background:
    Given the migration of brand "spinvibe" from the original

  Scenario: each imported affiliate is one account with one membership at the brand whose revenue and payouts fold to the original's figures
    Given the affiliates of brand "spinvibe"
    When the import has run
    Then each is one account with one membership at brand "spinvibe" on its plan
    And every period's revenue and payouts fold to the original's figures to the minor unit

  Scenario: a player attributed before the cutover has a deposit afterwards attributed to the same affiliate
    Given a player attributed before the cutover
    When they deposit after it
    Then the deposit is attributed to the same affiliate

  Scenario: a legacy tag link landed on after the cutover resolves through the legacy tag document and binds
    Given a legacy tag link of brand "spinvibe"
    When a visitor lands through it after the cutover
    Then the tag resolves through the legacy tag document
    And the attribution binds

  Scenario: the analytics reader's last position on the old facts is recorded and the new facts begin at the cutover, with nothing missing or doubled
    Given the analytics reader on the brand's export facts
    When the cutover switches
    Then its last position on the old facts is recorded
    And the new facts begin at the cutover point
    And no fact between the freeze and the switch is missing or doubled
