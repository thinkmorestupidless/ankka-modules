Feature: A suppressed player gets no marketing and still gets service sends
  A player self-excludes. From the fact the brand's engagement tracks it, sends the exclusion
  confirmation, identifies the player as suppressed with the reason, and from then on refuses
  every marketing class send for them until the exclusion ends. A player who withdraws email
  consent is unsubscribed in the CRM on the next identify; a service class send still reaches
  them. A closed player gets the closure confirmation and nothing after.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a marketing class trigger for a player with an open case is refused as suppressed and audited
    Given the player has an open case of exclusion or break
    When a marketing class trigger fires for them
    Then no send is made
    And the send record says "marketing_suppressed" with the reason
    And the refusal is an audit record

  Scenario: a service class trigger for a player with an open case is sent
    Given the player has an open case of exclusion or break
    When a service class trigger fires for them
    Then the send is made

  Scenario: a player who withdrew email consent is identified as unsubscribed and a marketing class email is refused
    Given the player withdrew email marketing consent
    When they are next identified
    Then "email_subscribed" is "false" with the time and the source
    And a marketing class email for them is refused

  Scenario: a closed player is refused every trigger but the closure confirmation
    Given the player is closed
    When any trigger but the closure confirmation fires for them
    Then it is refused with "account_closed"

  Scenario: an exclusion ending identifies the player as not suppressed and marketing resumes subject to consent
    Given the player's exclusion is ending
    When the "rg_case_closed" fact arrives
    Then the player is identified as not suppressed
    And marketing class sends resume, subject to consent
