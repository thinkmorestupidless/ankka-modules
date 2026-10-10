Feature: The game provider reads a balance or a player profile, or moves money outside a round
  The game provider asks for a balance with or without a session ticket, asks for a player's profile
  before creating free rounds, debits a buyin for a tournament or a table, or credits a win outside
  any round.

  Scenario: a balance read with a session ticket answers real plus restricted balance to two decimals and the currency
    Given a player with a session ticket for a wallet in "EUR"
    When the game provider asks the balance with the session ticket
    Then it is answered "ok" with real plus restricted balance to two decimals and the currency

  Scenario: a balance read with the player id and no session ticket resolves the player by id
    Given a player who has launched a game before
    When the game provider asks the balance with the player id and no session ticket
    Then the player is resolved by id
    And the balance is answered

  Scenario: a player profile read for a registered player who never launched answers their profile
    Given a registered player who has never launched a game
    When the game provider asks their player profile
    Then it is answered "ok" with their id, the brand's jurisdiction, their default currency, their registration country and date, their date of birth, and a bet limits band from their daily deposit limit

  Scenario: a player profile read for an unknown player id is answered player not found
    Given an unknown player id
    When the game provider asks the player profile for it
    Then it is answered "player_not_found"

  Scenario: a buyin places a bet keyed by its transaction id and answers the balance
    Given a player with a session ticket for a wallet in "EUR" and a real balance of "100.00"
    When the game provider debits a buyin of "10.00" under transaction id "t5"
    Then a bet keyed "t5" is placed
    And the balance is answered

  Scenario: a buyin the wallet cannot fund is answered not enough money
    Given a player with a session ticket for a wallet in "EUR" and a real balance of "5.00"
    When the game provider debits a buyin of "10.00" under transaction id "t5"
    Then it is answered "not_enough_money"

  Scenario: a credit outside any round is a provider credit keyed by its transaction id
    Given a player with a session ticket for a wallet in "EUR"
    When the game provider credits "15.00" outside any round under transaction id "t6"
    Then a provider credit keyed "t6" is made
    And the balance is answered
