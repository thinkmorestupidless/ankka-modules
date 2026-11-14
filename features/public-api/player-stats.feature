Feature: A caller ranks the brand's players without the ranking changing under it
  A caller ranks the brand's active players by rounds, sessions, stake, GGR or deposits over a
  window and pages through one snapshot. Another snapshot does not change the one being read; a
  retired snapshot is refused as expired. Rankings carry no identifying field.

  Scenario: paging a player ranking returns every ranked player once, in order, all pages naming one snapshot
    Given 250 players of brand "goldvibe" active in the last 7 days with known GGR in "EUR"
    When a caller pages the 7-day ranking by GGR in "EUR" 100 at a time
    Then it receives three pages naming one snapshot
    And every player exactly once, in descending order of GGR

  Scenario: another snapshot published mid-read does not change the pages of the snapshot being read
    Given a caller has read the first page of a snapshot
    When another snapshot is published
    And the caller asks for the second page naming the first snapshot
    Then the second page continues the first snapshot with no player repeated or missing

  Scenario: a snapshot retired after its retention is refused as expired
    Given a snapshot replaced more than its retention ago
    When a caller asks for a page naming it
    Then it is refused as an expired snapshot
    And a request naming no snapshot is served the current one

  Scenario: a ranking restricted to some statuses keeps each player's rank in the whole ranking
    Given a snapshot ranking active, closed and self-excluded players
    When a caller asks for the ranking of active players only
    Then only active players are listed, each with its rank in the whole ranking
    And the total counts only active players

  Scenario: one player's figures for all three windows match a hand count, and are zeros when they had no activity
    Given a player with known activity in the last 30 days and a player with none
    When a caller reads each one's figures
    Then the first's figures for 1, 7 and 30 days match a hand count
    And the second's are zeros with no last-active time

  Scenario: a ranking never carries an identifying field, whatever the key allows
    Given an access key allowed every identifying field
    When a caller reads a player ranking or a player's figures with it
    Then no identifying field is served
