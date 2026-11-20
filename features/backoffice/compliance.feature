Feature: Compliance work on identity and responsible gambling
  A compliance operator works the identity queue in arrival order and sees what each decision
  changes for the player before making it.

  Scenario: the identity queue is first-in first-out and keeps the operator's place
    Given an identity queue of three players whose documents arrived on three days
    When a compliance operator opens the queue, then the second player, then returns
    Then the queue is in the order the documents arrived
    And the operator is back at the second player

  Scenario: an identity document is viewed only by an operator the brand admits
    Given an identity document of a player
    When a support operator of another brand asks to view it
    Then the document is not shown

  Scenario: a player's raise of a limit is a pending change and the page shows when it applies
    Given a player with a daily deposit limit of "100.00 EUR" who has asked to raise it to "200.00 EUR"
    When a compliance operator opens the player's protection
    Then the operator is shown the limit is still "100.00 EUR" and "200.00 EUR" as a pending change with the time it applies

  Scenario: dismissing a fraud case clears the flags it set
    Given a fraud case that flagged two players as suspected duplicates
    When a compliance operator dismisses it with a note
    Then neither player is flagged as a suspected duplicate
