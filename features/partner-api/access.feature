Feature: A partner signs in and reads only its own brand
  A partner holding a client id and secret for one brand exchanges them for a sign-in and queries
  the brand's players, transactions, bets, rounds, sessions and rewards. Every record returned
  belongs to that brand. A record of any other brand is absent, not refused. A sign-in that is
  missing, expired, signed elsewhere, of the wrong actor type or role, or belonging to a partner
  whose entitlement is disabled, is refused and returns no data. The partner contract offers no
  write of any kind.

  Scenario: a partner with a valid sign-in for a brand is returned only that brand's players
    Given a partner holding a valid sign-in for brand "goldvibe"
    When it queries players
    Then every player returned belongs to brand "goldvibe"

  Scenario: a partner asking for another brand's player by id is answered empty without learning whether the id exists
    Given a partner holding a valid sign-in for brand "goldvibe"
    When it asks for a player of brand "spinvibe" by id
    Then the answer is empty
    And it does not say whether the id exists

  Scenario Outline: a request with a sign-in that cannot be trusted is refused and returns no data
    Given a request with <sign-in>
    When it arrives
    Then it is refused
    And no data is returned

    Examples:
      | sign-in                                     |
      | no sign-in                                  |
      | an expired sign-in                          |
      | a sign-in signed by another issuer          |
      | a sign-in whose actor type is not partner   |
      | a sign-in carrying the affiliate role       |

  Scenario: a partner whose entitlement an operator disabled is refused within a minute, even with a sign-in issued before
    Given a partner whose partner entitlement an operator disabled
    When it presents a sign-in issued before the change
    Then it is refused within one minute of the change

  Scenario: any operation that is not a query is refused, because the partner contract has no write and no subscription
    Given a partner holding a valid sign-in
    When it sends any operation that is not a query
    Then it is refused

  Scenario Outline: a partner with an entitlement but no realm client, or a realm client but no entitlement, is refused
    Given <situation>
    When a request arrives from it
    Then it is refused

    Examples:
      | situation                                                  |
      | a partner whose partner entitlement exists and no realm client does |
      | a partner whose realm client exists and no partner entitlement does |
