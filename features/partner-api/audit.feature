Feature: Every partner read is recorded, and an erased player stops appearing
  Every request leaves one audit record naming the partner, the operation and, where identifying
  fields were served, which players and which fields. When a player is erased, their identifying
  fields vanish from every answer, including a page a partner is part way through, without
  breaking paging; their amounts and ids remain. A subject access request can be answered.

  Scenario: a request that served identifying fields leaves one audit record naming the partner, the operation, the players and the field names, and no value
    Given a partner's request that served identifying fields for two players
    When it completes
    Then one audit record names the partner, the operation, the two opaque ids and the field names
    And it holds no field value

  Scenario: a request that served no identifying field still leaves an audit record saying so
    Given a partner's request that served no identifying field
    When it completes
    Then an audit record says so

  Scenario: an erased player read by a partner has every identifying field absent, keeps their id, status and amounts, and a page in progress continues without gaps
    Given an erased player
    When a partner reads them
    Then every identifying field is absent
    And the player's id, status and amounts remain
    And a page the partner was reading continues without gaps

  Scenario: a subject access request lists every partner served an identifying field of the player, with the fields and the times
    Given a subject access request for a player
    When an operator asks
    Then every partner that was served an identifying field of that player is listed with the fields and the times

  Scenario: no identifying value appears in a log, a trace or a metric of an error or a slow request
    Given an error or a slow request
    When it is logged or traced
    Then no identifying value appears in the log, the trace or any metric
