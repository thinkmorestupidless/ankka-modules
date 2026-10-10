Feature: Reads of KYC by operators and the player
  Operators list players by KYC state, see counts, and open a player's documents with their
  history; the player sees their own; other parts read facts.

  Scenario Outline: the queue read with a filter lists the matching players with a live count
    Given the brand's players
    When the queue is read with the filter "<filter>"
    Then the players matching are listed with a live count

    Examples:
      | filter                     |
      | docs awaiting review       |
      | KYC pending                |
      | verified                   |
      | rejected                   |
      | review required            |

  Scenario: the queue read with the ready for final review filter lists each player with the time since ready
    Given the brand's players
    When the queue is read with the filter "ready for final review"
    Then the players matching are listed with a live count and the time since each became ready

  Scenario: an operator reading a player's KYC tab sees every document and the account's KYC fields
    Given a player with documents
    When an operator reads the player's KYC tab
    Then every document is listed with category, status, upload time, review time, reviewer and reason code
    And the account's KYC status, mode, required categories, source of funds status, deadline and lifetime deposits are shown

  Scenario: a player's activity history shows KYC status changes as account events
    Given a player whose KYC status has changed
    When their activity history is read
    Then the KYC status changes appear as account entries
