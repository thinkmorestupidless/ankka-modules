Feature: A player's page is told when the inbox changes
  A signed-in page holds a push connection through the site server, since the sign-in is the site
  server's. A push makes the page read the unread count again. The inbox behaviour keeps the
  connection open and the count current.

  Background:
    Given a player site of brand "guyanabet"
    And a player signed in at the player site

  Scenario: a signed-in page holds a push connection through the site server
    When the page opens
    Then the page holds a push connection under the player's sign-in through the site server
    And the unread count is read

  Scenario: a push makes the page read the unread count again
    Given the page holds a push connection
    When a push arrives
    Then the unread count is read again

  Scenario: a push connection the brand closes is reopened after a wait that doubles to thirty seconds
    Given the page holds a push connection
    When the brand closes the push connection again and again
    Then it is reopened after "1" second, then "2", then "4", doubling to "30"

  Scenario: a push connection that opens resets the wait
    Given a push connection reopened after a wait of "8" seconds
    When the brand closes it again
    Then it is reopened after "1" second

  Scenario: a page that becomes visible reads the unread count without waiting for a push
    Given the page was hidden
    When it becomes visible
    Then the unread count is read again

  Scenario: signing out closes the push connection and it is not reopened
    Given the page holds a push connection
    When the player signs out
    Then the push connection is closed
    And it is not reopened
