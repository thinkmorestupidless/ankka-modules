Feature: The shared audit holds every brand's records and no real id
  The shared audit reads each brand's audit records under the brand's grant, replaces every subject
  and actor id with a pseudonym from its registry, and appends the record to its store. A query of
  the store, an export, a log line: nothing the shared audit emits carries a real id.

  Scenario: a record written in a brand that allows the shared audit appears in the shared store with pseudonyms in place of its ids and every other field unchanged
    Given a brand that allows the shared audit to read its audit records
    When a record is written in the brand
    Then it appears in the shared audit's store within the ingest bound
    And its subject and actor ids are replaced by pseudonyms
    And every other field is unchanged

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
