Feature: Every read is recorded, and each access key's limits are kept
  Every request that presents an access key of the brand leaves one audit record naming the key, the operation, the
  players and field names served, the duration and the outcome, and never a value. A key over its
  requests per minute is refused with when to retry, and no other key is slowed.

  Scenario: a request that served identifying fields leaves one audit record naming the key, the operation, the players and the field names, and no value
    Given an access key allowed email
    When a caller reads two players with it
    Then one audit record names the key, the operation, both players and the field "email"
    And it carries no email

  Scenario: a request that served no identifying field, or was refused, still leaves an audit record
    Given an access key allowed no identifying field, and a disabled access key
    When a request presents each
    Then each leaves one audit record with its outcome

  Scenario: a subject access request lists every key served an identifying field of the player, with the fields and the times
    Given two access keys were served a player's email
    When the player's subject access request is answered
    Then it lists both keys, the field and the times

  Scenario: a key at its requests per minute has the next request refused with when to retry, and another key is unaffected
    Given an access key at its requests per minute
    When it sends one more
    Then it is refused with when to retry
    And another access key's latency is unchanged

  Scenario: a request the service cannot answer within its time budget is told the service is temporarily unavailable
    Given a request that cannot be answered within five seconds
    When the budget is spent
    Then it is told the service is temporarily unavailable
