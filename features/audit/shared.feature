Feature: The shared audit holds every brand's records and no real id
  The shared audit reads each brand's audit records under the brand's grant, and payments' under
  that project's, replaces the id of every person a record names with a pseudonym from its registry,
  and appends the record to its store. A query of the store, an export, a log line: nothing the
  shared audit emits carries a person's real id.

  Scenario: a record written in a brand that allows the shared audit appears in the shared store with a pseudonym in place of every person's id and every other field unchanged
    Given a brand that allows the shared audit to read its audit records
    When an operator's action is recorded in the brand
    Then it appears in the shared audit's store within the ingest bound
    And the id of every person it names is replaced by a pseudonym
    And every other field is unchanged

  Scenario: a record about what is not a person keeps that id as written, and a person's id in it is a pseudonym
    Given a brand that allows the shared audit to read its audit records
    When an operator's action on a market is recorded in the brand
    Then the shared audit's record names the market by its id as written
    And names the operator by a pseudonym

  Scenario: a money event no person caused stays in the brand, and an operator's adjustment reaches the shared store with its amount
    Given a brand that allows the shared audit to read its audit records
    When a bet is placed and an operator adjusts the player's real balance with a reason code
    Then the bet's record is not in the shared audit's store
    And the adjustment's record is, with its amount

  Scenario: a player's records at one brand all name the same pseudonym
    Given a player with records at one brand
    When their records are read from the shared audit
    Then every record names the same pseudonym for the player

  Scenario: the same person at two brands has differing pseudonyms and nothing in the store joins them
    Given the same person registered at two brands
    When their records at each brand are read from the shared audit
    Then the pseudonyms differ
    And nothing in the store joins them

  Scenario: a record already in the store arriving again leaves the store unchanged
    Given a record already in the shared audit's store
    When the same record arrives again
    Then the store is unchanged

  Scenario: nothing the shared audit answers, writes or logs holds a real id, which only the registry holds
    Given everything the shared audit answers, writes or logs
    When it is searched for any real player, operator or affiliate id
    Then none is found
    And only the registry holds them

  Scenario: a brand that revokes what it allowed the shared audit stops the store at the last record received and shows the gap on the brand's status
    Given a brand that revoked what it allowed the shared audit
    When records are written in the brand
    Then the shared audit's store stops at the last record received
    And the brand's status in the shared audit shows the gap and since when

  Scenario: a payments record naming a brand is visible to that brand's compliance officer, and one naming no brand to the installation's alone
    Given payments allows the shared audit to read its audit records
    When payments records one action naming brand "goldvibe" and one naming no brand
    Then a compliance officer for brand "goldvibe" sees the first and not the second
    And the installation's compliance officer sees both
