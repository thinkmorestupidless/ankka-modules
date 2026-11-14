Feature: A caller with an access key reads only its own brand
  A caller presenting an access key reads the brand's players, their sessions, rounds, bets,
  transactions and bonus awards, and any of them by id. Everything returned is the key's brand's. A
  record of another brand is not found, exactly as one that does not exist. A request whose key
  cannot be trusted is refused and returns no data. Nothing can be written.

  Scenario: a caller with a key for a brand is returned only that brand's players
    Given a caller holding an access key of brand "goldvibe"
    When it lists players
    Then every player returned belongs to brand "goldvibe"

  Scenario: a caller asking for another brand's record by id is told it is not found, exactly as for a record that does not exist
    Given a caller holding an access key of brand "goldvibe"
    When it asks for a record of brand "spinvibe" by id
    Then it is told the record is not found
    And the answer is the same as for an id that does not exist

  Scenario Outline: a request with a key that cannot be trusted is refused and returns no data
    Given a request with <key>
    When it arrives at brand "goldvibe"
    Then it is refused
    And no data is returned

    Examples:
      | key                         |
      | no access key                  |
      | a malformed access key         |
      | an unknown access key          |
      | an access key of brand "spinvibe" |
      | a disabled access key          |
      | a deleted access key           |
      | an expired access key          |

  Scenario: any request that is not a read is refused, because the public contract has no write
    Given a caller holding an access key of brand "goldvibe"
    When it sends anything but a read
    Then it is refused as an invalid request

  Scenario: a malformed id is refused as an invalid request before anything is looked up
    Given a caller holding an access key of brand "goldvibe"
    When it asks for a record by a malformed id
    Then it is refused as an invalid request
