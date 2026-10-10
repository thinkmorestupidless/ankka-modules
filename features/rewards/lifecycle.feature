Feature: An active reward converts, completes, is switched, cancelled, revoked or expires
  The wallet reports what wagering does to the restricted funds; the brand's rewards keep the
  reward's state in step, set the clocks, and tell the player and the operators.

  Background:
    Given a player of brand "goldvibe" with an active reward

  Scenario: a conversion with a cap puts the reward in converted pending cap and sets a cap timer for the cap window
    Given the reward's restricted funds record has a maximum convertible amount
    When the wallet reports the record converted
    Then the reward is converted pending cap
    And a cap timer is set for the cap window's days

  Scenario: a conversion with no cap closes the reward as completed
    Given the reward's restricted funds record has no maximum convertible amount
    When the wallet reports the record converted
    Then the reward is closed as completed

  Scenario: a cap deadline passing closes the record on the wallet and the reward
    Given the reward is converted pending cap
    When the cap deadline passes
    Then the wallet closes the pending cap
    And the reward is closed

  Scenario Outline: a reward past its claim deadline or wagering window is forfeited on the wallet and expired
    Given the reward is past its <deadline>
    When the deadline passes
    Then the wallet forfeits the reward's record
    And the reward is expired

    Examples:
      | deadline        |
      | claim deadline  |
      | wagering window |

  Scenario: a player switching a second open reward to primary reorders the records on the wallet and records the switch
    Given a second open reward of the player
    When the player switches the second to primary
    Then the wallet reorders the records
    And the reward records the switch

  Scenario: a player cancelling a reward with an activation baseline forfeits the record and claws back winnings since activation less deposits since
    Given the wallet's record has a baseline from its award
    When the player cancels it
    Then the wallet forfeits the record
    And the wallet's clawback is the real winnings since the award less the deposits since, floored at zero

  Scenario: a player cancelling a reward with no activation baseline claws back nothing
    Given the wallet's record has no baseline
    When the player cancels it
    Then the wallet's clawback is "0"

  Scenario: a cancel naming a reward the player does not hold is answered reward not found
    Given a reward id the player does not hold
    When the player cancels it
    Then it is refused as "grant_not_found"

  Scenario: a cancel naming no active reward is refused as a conflict
    Given the player's reward is no longer active
    When the player cancels it
    Then it is refused as "NO_ACTIVE_BONUS"

  Scenario: an operator revoking a reward as forfeit forfeits the record and leaves real balance untouched
    Given the reward is active
    When an operator revokes it as "forfeit"
    Then the record is forfeited
    And the real balance is untouched

  Scenario: an operator revoking a reward as reclaim forfeits the record and applies the clawback
    Given the reward is active
    When an operator revokes it as "reclaim"
    Then the record is forfeited
    And the clawback is applied too

  Scenario: an operator revoking a reward with no mode is refused
    Given the reward is active
    When an operator revokes it with no mode
    Then it is refused as "INVALID_MODE"

  Scenario: a record an operator adjusted to zero closes the reward
    Given the reward is active
    When the wallet reports the reward's record adjusted to zero by an operator
    Then the reward is closed

  Scenario: a record an operator adjusted but not to zero leaves the reward active
    Given the reward is active
    When the wallet reports the reward's record adjusted above zero by an operator
    Then the reward stays active
