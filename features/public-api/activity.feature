Feature: A caller reads a player's activity page by page
  A caller lists the brand's players, oldest registration first, and per player their sessions,
  rounds, bets, transactions and bonus awards, oldest first. Each record carries its parents' ids.
  A page holds at most 100 records and gives the total and whether more follow.

  Scenario: paging a player's transactions at the maximum page size returns every one exactly once, oldest first
    Given a player of brand "goldvibe" with 250 transactions
    When a caller pages through their transactions 100 at a time
    Then it receives three pages
    And every transaction exactly once, oldest first

  Scenario: activity written while a caller is part way through paging is neither served again nor skipped
    Given a caller part way through paging a player's rounds
    When new rounds are played
    And it asks for the next page
    Then no round already served is served again
    And no older round is skipped

  Scenario: a page size above the maximum is refused, naming the maximum
    Given a caller holding an access key of brand "goldvibe"
    When it asks for a page of 101 records
    Then it is refused, and told the maximum is 100

  Scenario: a page past the end is empty and still gives the total
    Given a player with 30 sessions
    When a caller asks for the page starting at the 200th
    Then the page is empty
    And it gives a total of 30

  Scenario: a collection with nothing in it is an empty page, not an error
    Given a player with no bonus awards
    When a caller lists their bonus awards
    Then it receives an empty page

  Scenario: a record read by id carries the ids of its player and, where it has them, its session and round
    Given a bet in a round of a session of a player
    When a caller reads the bet by id
    Then it carries the ids of the player, the session and the round

  Scenario: an amount is served as an exact decimal in major units beside its currency
    Given a transaction of 1250 minor units of "EUR"
    When a caller reads it
    Then its amount is "12.50" with currency "EUR"

  Scenario: a status the public contract does not name is served as null, never mapped to another
    Given a player whose status the public contract does not name
    When a caller reads them
    Then their status is null

  Scenario: a closed or self-excluded player is listed and readable, with their status on the record
    Given a closed player and a self-excluded player
    When a caller lists players
    Then both are listed, each with their status
