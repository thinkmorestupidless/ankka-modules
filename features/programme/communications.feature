Feature: The programme talks to affiliates
  Affiliates are told what happened to them: by in-portal notification, by email, and by postback
  to their own system.

  Scenario: a postback is sent for a registration, first deposit, deposit or commission with the click id, payout amount and status substituted
    Given an affiliate with a postback address
    When a registration, first deposit, deposit or commission fact occurs for their player
    Then a postback is sent with the kind, the click id, the payout amount and the status substituted

  Scenario: a failed postback is retried with backoff for up to a day and its last outcome is visible
    Given a postback that failed
    When it is retried
    Then it is retried with backoff for up to "24" hours
    And the last outcome is visible to the affiliate and to a manager

  Scenario Outline: an affiliate is told what happened to them by notification and by email, and a failed send never blocks the action
    Given an affiliate
    When <happening>
    Then an in-portal notification is written
    And a transactional email is sent with the brand's sender configuration
    And a failed send never blocks the action

    Examples:
      | happening                        |
      | their application is received    |
      | their application is approved    |
      | their application is rejected    |
      | their payout is paid             |
      | they are invited                 |
      | they ask for a password reset    |

  Scenario: a parent is told when a sub's application is pending approval
    Given a sub who applies under a parent
    When the application is recorded
    Then the parent is told that an approval is pending

  Scenario: reading notifications or marking all read marks them read for that affiliate only
    Given an affiliate with notifications
    When they read them or mark all read
    Then the notifications are marked read for that affiliate only
