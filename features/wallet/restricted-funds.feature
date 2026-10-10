Feature: Restricted funds are awarded, wagered, converted and forfeited
  An award creates a restricted funds record with a snapshot of its rules. Bets draw on the records
  in priority order, each bet's contribution advances release progress, and a record whose progress
  meets its wagering requirement converts: its balance becomes real and it awaits its cap so a
  later withdrawal can cap what the player takes out. An award can be forfeited, cancelled with a
  clawback, closed once its cap is settled, or made primary.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: an award creates an active restricted funds record with its wagering requirement
    Given the wallet has a real balance of "0"
    When restricted funds of "2000" are awarded with a wagering multiplier of "10"
    Then the restricted balance is "2000"
    And one restricted funds record is open and active with a wagering requirement of "20000" and release progress of "0"
    And a "bonus_credit" transaction is written

  Scenario: under real first funding with partial splits on, a bet real balance cannot cover alone is split
    Given an active restricted funds record of "2000" and a real balance of "1000"
    And the funding order is "real_first" with partial splits on
    When the player places a bet of "1500" in round "r1"
    Then the bet's real leg is "1000" and its restricted funds leg is "500"
    And the bet's wallet type is "split"
    And the record's open round count is "1"

  Scenario: under real first funding with partial splits off, a bet real balance cannot cover alone is funded from the record in full
    Given an active restricted funds record of "2000" and a real balance of "1000"
    And the funding order is "real_first" with partial splits off
    When the player places a bet of "1500" in round "r1"
    Then the record funds all "1500"
    And the bet's wallet type is "bonus"

  Scenario: records fund a bet in priority order, the first drawn to zero before the next
    Given active restricted funds records "A" of "300" at priority "0" and "B" of "1000" at priority "1"
    When the player places a bet of "800" funded from restricted funds
    Then record "A" is drawn to "0" first
    And record "B" funds the remaining "500"

  Scenario: a record whose release progress meets its wagering requirement converts on the bet that completes it
    Given an active restricted funds record with a wagering requirement of "20000" and release progress of "19500" at a contribution rate of "100"
    When the player places a bet of "1000" funded from real balance
    Then the record converts: its balance is credited to real balance
    And the record awaits its cap with its remaining allowance set to its maximum convertible amount
    And the bet reports the conversion

  Scenario: a win follows its source, with the last restricted funds leg absorbing the rounding remainder
    Given a bet in round "r1" funded half from real balance and half from a restricted funds record under win allocation "follow_source"
    When a win of "1001" is credited for round "r1"
    Then real balance receives "500" and the record receives "501"
    And the record's open round count falls by one

  Scenario: a win's share for a record that has since converted is paid to real balance
    Given a bet in round "r1" funded partly from a restricted funds record that has since converted
    When a win of "1000" is credited for round "r1"
    Then the record's share is paid to real balance and not to the converted record

  Scenario: a bet on a game a record's scope excludes with violation mode block is refused
    Given an active restricted funds record whose scope excludes the game "slot-x" with violation mode "block"
    When the player places a bet of "100" on the game "slot-x"
    Then the bet is refused as "scope blocked"

  Scenario: a bet above a maximum stake enforced as cap is funded in full with its contribution base capped
    Given an active restricted funds record with a maximum stake of "500" enforced as "cap"
    When the player places a bet of "800" in round "r1"
    Then the bet is funded in full
    And the bet's contribution base is "500"

  Scenario: a bet above a maximum stake enforced as block is refused
    Given an active restricted funds record with a maximum stake of "500" enforced as "block"
    When the player places a bet of "800" in round "r1"
    Then the bet is refused as "maximum stake exceeded"

  Scenario: a record at zero balance is forfeited when its last open round settles as a loss
    Given an active restricted funds record with a balance of "0" and one open round "r1"
    When round "r1" is settled as a loss
    Then the record is forfeited
    And the restricted balance is "0"

  Scenario: forfeiting an active record zeroes it and closes it as forfeited
    Given an active restricted funds record of "2000"
    When the restricted funds are forfeited
    Then the record's balance is "0"
    And the record is forfeited

  Scenario: forfeiting when no record is active is refused
    Given no active restricted funds record
    When the restricted funds are forfeited
    Then it is refused as "no active restricted funds"

  Scenario: cancelling an award forfeits its record and applies the clawback clamped to real balance
    Given an active restricted funds record of "2000" and a real balance of "300"
    And the clawback computed from the record's baseline is "500"
    When the award is cancelled
    Then the record is forfeited
    And the clawback applied is "300"
    And the real balance is "0"

  Scenario: cancelling with no active record still applies the clawback to real balance
    Given no active restricted funds record and a real balance of "300"
    And the clawback computed from the award's baseline is "500"
    When the award is cancelled
    Then no record changes
    And the clawback applied is "300"
    And a "bonus_cancel" transaction is written

  Scenario: closing the pending cap of a converted record closes it with no balance change
    Given a restricted funds record awaiting its cap
    When the pending cap is closed
    Then the record is closed
    And no balance changes

  Scenario: closing a pending cap when no record awaits one is refused
    Given no restricted funds record awaiting its cap
    When the pending cap is closed
    Then it is refused as "not pending cap"

  Scenario: making another active award primary makes its record sort first on the next bet
    Given active restricted funds records "A" as primary and "B"
    When the award of "B" is made primary
    Then record "B" sorts first on the next bet

  Scenario: making an award that is not active primary is refused
    Given a forfeited restricted funds record "C"
    When the award of "C" is made primary
    Then it is refused as "award not active"

  Scenario: making an unknown award primary is refused
    Given no restricted funds record for award "Z"
    When the award of "Z" is made primary
    Then it is refused as "award not found"

  Scenario: a second award is refused when the brand allows one concurrent award
    Given the brand allows "1" concurrent award and one restricted funds record is active
    When a second award of "1000" arrives
    Then it is refused as "restricted funds already active"

  Scenario: a second award is accepted when the brand sets no limit on concurrent awards
    Given the brand sets no limit on concurrent awards and one restricted funds record is active
    When a second award of "1000" arrives
    Then it is accepted

  Scenario: a wallet with a restricted balance and no open record refuses every bet-path command
    Given a restricted balance of "500" with no open restricted funds record
    When the player places a bet of "100" in round "r1"
    Then the bet is refused as "restricted funds unmigrated"
