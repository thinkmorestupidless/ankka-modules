Feature: Reads of withdrawal requests by the player and by the operator
  The player reads their requests with amount, terminal, time and status; the operator reads the
  queue, filtered by status and by the withdrawal payment's status, so that what needs review is
  visible at a glance.

  Scenario: a player lists their requests newest first with amount, terminal, time and status
    Given three withdrawal requests over a player's life
    When the player lists them
    Then all three are shown newest first with amount, terminal, time and status

  Scenario: a queue filtered by status pending review shows only pending requests
    Given a queue with requests pending review, approved and initiated, and marked needs review
    When an operator filters it by status "pending review"
    Then only the requests pending review are shown

  Scenario: a queue filtered by payment status needs review shows only those requests
    Given a queue with requests pending review, approved and initiated, and marked needs review
    When an operator filters it by the withdrawal payment's status "needs review"
    Then only the requests marked needs review are shown
