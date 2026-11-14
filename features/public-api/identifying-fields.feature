Feature: A caller is served only the identifying fields its access key allows
  An operator chooses, per access key, which identifying fields it may read; none by default. A field a
  key does not allow is absent from the answer, never empty. An erased player's identifying fields
  are absent whatever the key allows.

  Scenario: a key with no identifying fields reads players with every identifying field absent
    Given an access key allowed no identifying field
    When a caller reads a player with it
    Then every identifying field is absent from the answer

  Scenario: a key allowed some identifying fields is served those and no others
    Given an access key allowed only email
    When a caller reads a player with it
    Then the answer carries the player's email
    And every other identifying field is absent

  Scenario: an identifying field withdrawn from a key stops arriving from the next request
    Given an access key allowed email
    When an operator withdraws email from it
    Then the next player read with it has no email

  Scenario: an erased player read with any key has every identifying field absent and keeps their id, status and amounts
    Given an erased player
    When a caller reads them with an access key allowed every identifying field
    Then every identifying field is absent
    And their id, status and amounts are served

  Scenario: no identifying value appears in a log, a trace or an error
    Given callers reading players with access keys allowed every identifying field
    When their requests succeed, fail or are slow
    Then no identifying value appears in any log, trace or error
