Feature: A verified player requests a withdrawal and the money is held
  A player with verified identity, no deposit turnover pending and no restricted funds in play
  chooses an amount and an eligible terminal, supplies what the terminal needs, and submits. The
  wallet holds the amount at once, the withdrawal request appears in the player's account as
  pending review and in the brand's queue, and a fact is emitted. The gates before the hold each
  tell the player exactly why and what to do next.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: a request by a verified player with nothing pending holds the amount and is listed pending review
    Given the player is verified, with a real balance of "10000", no deposit turnover pending and no restricted funds
    When the player requests a withdrawal of "5000" to an eligible bank terminal naming their saved destination
    Then the withdrawal request is created pending review
    And the real balance is "5000"
    And the request is listed in the player's account and in the brand's queue
    And a "withdrawal_requested" fact names the request, "5000" and "EUR"

  Scenario: a second request while one is pending review is refused and nothing moves
    Given a withdrawal request pending review
    When the player requests a withdrawal again
    Then it is refused as already pending
    And nothing moves

  Scenario Outline: an amount that is zero, negative or a decimal is refused naming the amount
    Given the player is verified with a real balance of "10000"
    When the player requests a withdrawal of "<amount>"
    Then it is refused naming the amount
    And nothing moves

    Examples:
      | amount |
      | 0      |
      | -100   |
      | 10.50  |

  Scenario: a request to a terminal that requires a destination is refused when none is named
    Given a terminal that requires a destination
    When the player requests a withdrawal to it naming no destination
    Then it is refused as destination required

  Scenario: a request to a crypto terminal is refused when no address is supplied
    Given a crypto terminal
    When the player requests a withdrawal to it naming no crypto address
    Then it is refused as crypto address required

  Scenario: a request to a crypto terminal naming a coin it does not support is refused naming the coins it supports
    Given a crypto terminal supporting coins "BTC" and "ETH"
    When the player requests a withdrawal to it naming coin "DOGE"
    Then it is refused naming "BTC" and "ETH"

  Scenario Outline: a destination that is not the player's own is refused as invalid
    Given <destination>
    When the player requests a withdrawal naming it
    Then it is refused as an invalid destination

    Examples:
      | destination                           |
      | a destination that is another player's |
      | a destination the player deleted       |

  Scenario: a request above real balance is refused as insufficient funds and nothing moves
    Given the player is verified with a real balance of "3000"
    When the player requests a withdrawal of "5000"
    Then it is refused as insufficient funds
    And nothing moves

  Scenario Outline: a player whose identity is not verified is refused telling them what to do
    Given the player's identity documents are <state>
    When the player requests a withdrawal
    Then it is refused telling them <told>

    Examples:
      | state         | told                                                      |
      | not submitted | to verify their identity                                  |
      | under review  | that the documents are under review                       |
      | rejected      | that the documents were not accepted and must be sent again |

  Scenario: a verified player passes the identity gate
    Given the player's identity is verified
    When the player requests a withdrawal
    Then the identity gate passes

  Scenario: a request while deposit turnover is pending is refused naming what remains
    Given deposit turnover pending of "25000"
    When the player requests a withdrawal
    Then it is refused as blocked by turnover, naming "25000" "EUR" remaining

  Scenario: a request whose clawback under a funds and winnings policy would leave real balance short is refused naming the most they could withdraw
    Given an active restricted funds record under withdrawal policy "funds_and_winnings" whose clawback would leave real balance below the amount
    When the player requests a withdrawal
    Then it is refused as insufficient after forfeit, naming the most they could withdraw

  Scenario: a request with a record that forfeits on withdrawal is refused until the player confirms the forfeit
    Given an active restricted funds record whose withdrawal policy forfeits it, with a clawback that still leaves enough
    When the player requests a withdrawal without confirming the forfeit
    Then it is refused as requiring forfeit confirmation, naming the restricted balance, the clawback and the total forfeit

  Scenario: a request confirming the forfeit places the hold, forfeits the record and applies the clawback once
    Given an active restricted funds record whose withdrawal policy forfeits it, with a clawback that still leaves enough
    When the player requests a withdrawal confirming the forfeit
    Then the hold is placed
    And the record is forfeited
    And the clawback is applied once
    And the answer says what was forfeited

  Scenario: a request with a record under a funds policy requires confirmation naming a clawback of zero
    Given an active restricted funds record under withdrawal policy "funds"
    When the player requests a withdrawal without confirming the forfeit
    Then it is refused as requiring forfeit confirmation, naming the restricted balance and a clawback of "0"

  Scenario: a request above real balance alone with restricted funds in play is refused as insufficient funds, not as insufficient after forfeit
    Given a real balance of "3000" and an active restricted funds record
    When the player requests a withdrawal of "5000"
    Then it is refused as insufficient funds
    And not as insufficient after forfeit
