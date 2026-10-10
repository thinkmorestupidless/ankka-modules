Feature: The profile the CRM holds reflects the brand's facts
  An operator building a lapsed-player CRM campaign segments on the player's attributes. Every identify
  the brand's engagement sends carries the full profile, from facts and never from a read of any
  other part's store, with numeric attributes as numbers. When the deposit day rolls over, every
  player whose daily deposit count was above zero is identified again with zero.

  Background:
    Given a player of brand "goldvibe"

  Scenario: the identify before any track carries every attribute whose source fact has been seen, as numbers where numeric
    Given the facts of the player's life so far
    When any track is made
    Then the identify that precedes it carries every attribute whose source fact has been seen
    And it omits the rest
    And the numeric attributes are numbers

  Scenario: the deposit day rolling over identifies a player who deposited today with a daily deposit count of zero, and not one already at zero
    Given the player deposited twice today by the brand's deposit day
    When the deposit day rolls over
    Then one identify with "daily_deposit_count" of "0" is sent for the player
    And none is sent for a player whose count was already "0"

  Scenario: a tier change or a potential VIP mark identifies the player with the new value and tracks nothing
    Given an operator changes the player's tier or marks them a potential VIP
    When the fact arrives
    Then the player is identified with the new value
    And no track is made

  Scenario: a player with no row in the balance view is identified with the balance attributes absent, not zero
    Given the balance view has no row for the player
    When they are identified
    Then the balance attributes are absent, not zero
