Feature: A player deposits through the deposit behaviour
  The deposit behaviour is a deposit as steps: the amount, the brand's start of the deposit, the
  terminals the cashier session offers, any details the terminal needs, the submit, and the wait for
  the payment's outcome. The brand starts the deposit only after the deposit gate, the deposit limit
  and a break have been asked, and a refusal is a step the behaviour cannot go past.

  Background:
    Given a player site of brand "guyanabet"
    And a player signed in at the player site whose currency is "EUR"

  Scenario: a deposit opens at the amount with the site's preset amounts in the player's currency
    When the player opens the deposit
    Then the deposit behaviour stands at the amount
    And it offers the site's preset amounts in "EUR" as minor units

  Scenario: confirming an amount has the brand start the deposit and offers the cashier session's terminals
    Given the deposit behaviour stands at the amount
    When the player confirms "5000" minor units
    Then the brand is asked once to start a deposit of "5000" minor units of "EUR" under a new request id
    And the deposit behaviour stands at choosing a terminal with the terminals the cashier session offers

  Scenario Outline: a deposit the brand refuses stands at refused naming the reason and no cashier session is opened
    Given the brand refuses to start the deposit as "<code>"
    When the player confirms an amount
    Then the deposit behaviour stands at refused with the code "<code>" and <fields>
    And no cashier session is opened
    And the only actions allowed are going back to the amount and closing

    Examples:
      | code                 | fields                                                 |
      | DEPOSIT_BLOCKED_KYC  | the KYC status                                         |
      | DEPOSIT_BLOCKED_KYC_THRESHOLD | the KYC status                                |
      | DEPOSIT_BLOCKED_KYC_CURRENCY  | the KYC status                                |
      | DEPOSIT_BLOCKED_SOF  | no fields                                              |
      | over_limit           | the period of the deposit limit, its value, what was used and what remains |
      | cooling_off          | the end of the break                                   |
      | excluded             | no fields                                              |
      | account_restricted   | the player status                                      |

  Scenario: a start that was not answered stands at refused naming the service and may be tried again under the same request id
    Given the brand does not answer the start of the deposit
    When the player confirms "5000" minor units
    Then the deposit behaviour stands at refused with the code "service_unavailable" and the service that did not answer
    When the player tries again
    Then the brand is asked to start a deposit of "5000" minor units of "EUR" under the same request id

  Scenario: a deposit the cashier refuses stands at refused with the cashier's code
    Given the cashier refuses the cashier session as "TOO_MANY_REQUESTS"
    When the player confirms an amount
    Then the deposit behaviour stands at refused with the code "TOO_MANY_REQUESTS" and the service "ingest"

  Scenario: going back from a refused deposit returns to the amount
    Given the deposit behaviour stands at refused
    When the player goes back
    Then the deposit behaviour stands at the amount

  Scenario: a cashier session offering no terminal ends the deposit as failed
    Given the brand starts the deposit with a cashier session that offers no terminal
    When the player confirms an amount
    Then the deposit behaviour stands at failed with the code "no_terminals"

  Scenario: choosing a terminal whose details the player profile holds submits the cashier session at once
    Given the deposit behaviour stands at choosing a terminal
    And the player profile holds the details the terminal "card-eur" needs
    When the player chooses the terminal "card-eur"
    Then the cashier session is submitted once naming "card-eur" under its cashier ticket

  Scenario Outline: choosing a terminal whose scheme needs details the player profile lacks asks for them, saves them and submits with them
    Given the deposit behaviour stands at choosing a terminal
    And the player profile lacks <missing>
    When the player chooses a terminal of the scheme "<scheme>"
    Then the deposit behaviour stands at details asking for <missing>
    When the player gives the details
    Then the details are saved to the player profile
    And the cashier session is submitted with the details

    Examples:
      | scheme            | missing                                |
      | SKRILL            | the first and last name                |
      | VISA              | the telephone number and the address   |
      | APPLE_PAY         | the telephone number                   |
      | INTERAC_ETRANSFER | the mobile number and the province     |

  Scenario: a terminal of a scheme that needs no details submits at once
    Given the deposit behaviour stands at choosing a terminal
    When the player chooses a terminal of the scheme "PIX"
    Then the cashier session is submitted once

  Scenario: a crypto terminal asks for the coin and then shows where to pay
    Given the deposit behaviour stands at choosing a terminal
    When the player chooses a terminal of the scheme "CRYPTO"
    Then the deposit behaviour stands at choosing a coin
    When the player chooses the coin "USDT"
    Then the cashier session is submitted naming the coin "USDT"
    And the deposit behaviour stands at waiting on a crypto payment with the address, the amount, the coin and the expiry

  Scenario: a payment awaiting the player at the payment provider gives the page the provider's address and waits
    Given the cashier session was submitted
    When the payment is read as awaiting the player with a redirect address
    Then the deposit behaviour stands at waiting at the payment provider with that address

  Scenario: a captured payment ends the deposit as succeeded and the balance is read again
    Given the deposit behaviour stands at waiting at the payment provider
    When the payment is read as captured
    Then the deposit behaviour stands at succeeded
    And the player's balance is read again

  Scenario Outline: a payment that fails or expires ends the deposit as failed with the payment's failure code
    Given the deposit behaviour stands at waiting at the payment provider
    When the payment is read as <outcome>
    Then the deposit behaviour stands at failed with the payment's failure code

    Examples:
      | outcome |
      | failed  |
      | expired |

  Scenario: a submit the payment provider rejects ends the deposit as failed with the provider's words
    Given the deposit behaviour stands at choosing a terminal
    When the player chooses a terminal and the payment provider rejects the submit with words for the player
    Then the deposit behaviour stands at failed with the code "PAYMENT_REJECTED" and the provider's words

  Scenario: the cashier ticket is in no step and in nothing the page keeps
    Given the deposit behaviour stands at choosing a terminal
    When the page reads the step and everything the page keeps
    Then the cashier ticket is in neither

  Scenario: a payment with no outcome in twenty minutes ends the deposit as timed out
    Given the deposit behaviour stands at waiting at the payment provider
    When twenty minutes pass and the payment has no outcome
    Then the deposit behaviour stands at failed with the code "timed_out"

  Scenario: a payment read that fails while waiting is ignored
    Given the deposit behaviour stands at waiting at the payment provider
    When a read of the payment is not answered
    Then the deposit behaviour still stands at waiting at the payment provider
    And the payment is read again

  Scenario: closing a waiting deposit abandons the cashier session
    Given the deposit behaviour stands at waiting at the payment provider
    When the player closes the deposit
    Then the cashier session is abandoned under its cashier ticket

  Scenario: an action the step does not allow sends nothing and is answered as not allowed
    Given the deposit behaviour stands at the amount
    When the page asks the deposit behaviour to submit
    Then nothing is sent to the brand
    And the page is told the action is not allowed at the amount

  Scenario: an amount confirmed twice starts one deposit
    Given the deposit behaviour stands at the amount
    When the player confirms an amount twice
    Then the brand is asked once to start a deposit

  Scenario: a visitor arriving at the site's deposit address is asked to sign in and the deposit opens once they have
    Given a visitor of the player site
    When they arrive at the site's deposit address
    Then they are asked to sign in
    When they sign in
    Then the deposit behaviour stands at the amount
