Feature: Running the affiliate programme from the backoffice
  Managers run the programme and see nothing else of a brand.

  Scenario: a manager sees only the programme, and only the brands their roles name
    Given a manager whose roles name the brand "goldvibe"
    When the manager signs in to the backoffice
    Then the manager sees the programme for "goldvibe" only
    And sees no player, wallet or withdrawal request

  Scenario: dismissing a fraud flag records the reason
    Given a fraud flag raised for a player of the brand
    When a manager dismisses it with the reason "known high roller"
    Then the fraud flag is dismissed with that reason and the manager's name
