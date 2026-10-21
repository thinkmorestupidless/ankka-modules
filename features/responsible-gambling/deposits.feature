Feature: A deposit is refused before it is credited
  A player with a daily deposit limit who has reached it tries to deposit more. The wallet asks
  responsible gambling before crediting and the deposit is refused, naming what remains. A player
  on a break or excluded is refused whatever the amount.

  Background:
    Given a player of brand "goldvibe" with a wallet in "EUR"

  Scenario: a deposit over the daily deposit limit is refused naming the limit, what was used and what remains, and no balance changes
    Given a daily deposit limit of "100" and "80" deposited in the last "24" hours
    When a deposit of "30" arrives
    Then it is refused as over the limit, naming the daily deposit limit, "80" used and "20" remaining
    And no balance changes

  Scenario: a deposit exactly at what remains of the daily deposit limit is accepted
    Given a daily deposit limit of "100" and "80" deposited in the last "24" hours
    When a deposit of "20" arrives
    Then it is accepted

  Scenario: a deposit a minor unit over what remains of the daily deposit limit is refused
    Given a daily deposit limit of "100" and "80" deposited in the last "24" hours
    When a deposit of "20.01" arrives
    Then it is refused

  Scenario: a deposit that would pass any of the daily, weekly or monthly deposit limits is refused naming the first it would pass
    Given a daily deposit limit of "100", a weekly deposit limit of "300" and a monthly deposit limit of "500"
    When a deposit would pass any of the three
    Then it is refused naming the first limit it would pass, in that order

  Scenario: a deposit for a player on a break is refused as cooling off naming the end of the break
    Given the player is on a break until tomorrow
    When any deposit arrives
    Then it is refused as cooling off, naming the end of the break

  Scenario: a deposit for a self-excluded player is refused as excluded
    Given the player is self-excluded
    When any deposit arrives
    Then it is refused as excluded

  Scenario: a deposit for a player whose break ended is not refused for the break
    Given the player's break ended an hour ago
    When a deposit arrives
    Then the break does not refuse it

  Scenario: a deposit while responsible gambling cannot be reached is refused as unavailable and credits nothing
    Given responsible gambling cannot be reached
    When the wallet asks it about a deposit
    Then the wallet refuses the deposit as unavailable
    And nothing is credited

  Scenario: a deposit refused while responsible gambling could not be reached is checked again when it arrives again
    Given a deposit the wallet refused as unavailable
    And responsible gambling can be reached again
    When the same deposit arrives again
    Then the wallet asks responsible gambling about it again
    And the deposit is credited once

  Scenario: a deposit refused for a limit is recorded captured and not credited, and the player is told the remaining allowance
    Given a deposit refused for a deposit limit
    When the cashier learns of it
    Then the payment is recorded captured and not credited, with the reason
    And the player is told the remaining allowance in their currency
