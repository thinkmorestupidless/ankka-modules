Feature: A sub-brand opens a cashier session under its key, submits it and reads the payment
  A brand's cashier page, or an outside sub-brand's own server, asks the shared payments for a
  cashier session under its cashier key: this amount, this currency, this player, this country, this
  request id. The shared payments verifies the key, checks the request, and answers with a cashier
  ticket and the terminals the sub-brand's routing tree allows. The page submits the session naming
  a terminal and is given the payment id and where to send the player; a repeat does nothing twice;
  a sub-brand that floods is slowed and the rest are not.

  Scenario: a cashier session created under an active key answers a cashier ticket and the terminals the routing tree allows
    Given a sub-brand with an active cashier key and a deployed routing tree whose root offers terminal "sim-eur" for amounts under "100000" minor units in "EUR"
    When the sub-brand creates a cashier session for "5000" minor units of "EUR" for player "goldvibe:p1" in "GB" under its key
    Then it is answered with a cashier ticket and "sim-eur" as the one available terminal

  Scenario: submitting a session naming an offered terminal initiates the payment and answers the payment id and the status
    Given a cashier session for "5000" minor units of "EUR" offering terminal "sim-eur"
    When the sub-brand submits it naming terminal "sim-eur"
    Then the gateway is asked to initiate a hosted page payment with the payment provider
    And the payment is recorded as awaiting redirect with a deadline "30" minutes away
    And it is answered with the payment id and the status, and not the redirect address

  Scenario: reading a payment tells its status in the frontend's words with its amounts, currency, provider, scheme, terminal and redirect address
    Given a payment awaiting redirect
    When the player's frontend reads the payment
    Then it is told the status as "COMPLETED", "FAILED", "EXPIRED" or the awaiting status, the amount as a decimal and in minor units, the currency, the payment provider, the scheme, the terminal and the redirect address

  Scenario Outline: a cashier session that cannot be routed is refused naming why
    Given <situation>
    When the sub-brand creates a cashier session
    Then it is refused as "<reason>"

    Examples:
      | situation                                                   | reason             |
      | a request naming no country or no player id                 | no routing context |
      | a sub-brand with no routing tree deployed at any level      | no routing tree    |
      | a sub-brand whose routing tree has no branch that matches   | no routing match   |

  Scenario: a routing refusal is recorded against the sub-brand
    Given a sub-brand whose routing tree matches nothing for the player's country
    When the sub-brand creates a cashier session for that player
    Then it is refused naming the routing reason
    And the refusal is recorded against the sub-brand

  Scenario: a request with no cashier key is refused as unauthenticated and no session exists
    Given a request presenting no cashier key
    When it creates a cashier session
    Then it is refused as unauthenticated
    And no cashier session exists

  Scenario Outline: a request under a key no sub-brand holds active is refused without saying why
    Given a request presenting <key>
    When it creates a cashier session
    Then it is refused as "invalid cashier key"
    And the refusal does not say which of the two it was

    Examples:
      | key                                 |
      | a cashier key no sub-brand holds    |
      | a cashier key a sub-brand disabled  |

  Scenario Outline: a request missing or malforming a field is refused naming the field before the cashier is asked
    Given a sub-brand with an active cashier key
    When it creates a cashier session with <field>
    Then it is refused naming the field
    And the cashier is not asked

    Examples:
      | field                                                |
      | an amount that is not a whole number of minor units  |
      | no amount                                            |
      | no currency                                          |
      | a player id without the brand part                   |

  Scenario: a repeated create under the same request id with the same content returns the first session and asks the cashier nothing
    Given a cashier session created under request id "t-1"
    When the same sub-brand creates one under request id "t-1" again with the same content
    Then the first session's cashier ticket and terminals are returned
    And the cashier is not asked again

  Scenario: a repeated create under the same request id with a different amount is refused naming the request id
    Given a cashier session created under request id "t-1"
    When the same sub-brand creates one under request id "t-1" with a different amount
    Then it is refused as a request id already used with a different request, naming "t-1"

  Scenario: a request id belongs to the sub-brand and not to the installation
    Given two sub-brands
    When each creates a cashier session under request id "t-1"
    Then each has its own session

  Scenario: a create with no request id is served under one the shared payments mints, and the same content again makes a second session
    Given a sub-brand with an active cashier key
    When it creates a cashier session with no request id
    Then it is served under a request id the shared payments mints and returns
    And the same content sent again creates a second session

  Scenario: submitting a session already submitted returns the first submit's answer and makes no second payment
    Given a cashier session already submitted
    When the sub-brand submits it again
    Then the first submit's answer is returned
    And no second payment exists

  Scenario: submitting a session naming a terminal it did not offer is refused naming the terminal
    Given a cashier session offering terminal "sim-eur" only
    When the sub-brand submits it naming terminal "sim-gbp"
    Then it is refused naming "sim-gbp"

  Scenario: a submit the payment provider refused for a reason the player can act on is answered rejected with the provider's words
    Given a cashier session whose submit the payment provider refuses for a reason the player can act on
    When the answer comes back
    Then the sub-brand is told the submit was rejected, with the payment provider's words, so that the cashier page shows them

  Scenario: a submit under a cashier ticket no session has is refused as not found
    Given a cashier ticket no session has
    When it is submitted
    Then it is refused as not found

  Scenario: a payment read by the sub-brand that created it returns its state
    Given a payment created by sub-brand "acme"
    When sub-brand "acme" reads it by its id
    Then its state is returned

  Scenario: a payment read by another sub-brand is refused as not found
    Given a payment created by sub-brand "acme"
    When sub-brand "bravo" reads it by its id
    Then it is refused as not found

  Scenario: a sub-brand above the rate limit has the excess refused as too many with the time to wait, and the cashier receives only what was served
    Given the installation's rate limit of "600" requests a minute
    When a sub-brand sends "700" creates in a minute
    Then "600" are served
    And "100" are refused as too many, with the time to wait
    And the cashier receives "600"

  Scenario: one sub-brand refused as too many leaves another sub-brand served in the same minute
    Given a sub-brand refused as too many
    When another sub-brand sends a create in the same minute
    Then it is served

  Scenario: a sub-brand whose rate limit an operator raised is served in full
    Given a sub-brand whose rate limit an operator raised to "6000"
    When it sends "700" creates in a minute
    Then all "700" are served
