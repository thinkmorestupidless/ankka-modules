Feature: A player sets, lowers, raises and removes a limit
  A player opens their limits and sets a deposit limit. Lowering it applies at once. Raising or
  removing it is a pending change that applies 24 hours later unless they tighten it again first.
  They see what applies, what is pending and when, and what they have used.

  Background:
    Given a player of brand "goldvibe" with a wallet in "EUR"

  Scenario: a first daily deposit limit applies at once and shows with the amount used in the last day
    Given the player has no deposit limit
    When they set a daily deposit limit of "100"
    Then it applies at once
    And their profile shows it with the amount deposited in the last "24" hours

  Scenario: lowering a deposit limit applies at once and cancels any pending change for it
    Given a daily deposit limit of "100"
    When the player sets it to "50"
    Then "50" applies at once
    And any pending change for that limit is cancelled

  Scenario: raising a deposit limit keeps the old one and applies the new one a day later
    Given a daily deposit limit of "50"
    When the player sets it to "200"
    Then "50" still applies
    And the profile shows "200" as a pending change with a time "24" hours from now
    And at that time "200" applies and the pending change is gone

  Scenario: removing a deposit limit keeps it for a day and then no limit applies
    Given a daily deposit limit of "50"
    When the player removes it
    Then "50" still applies
    And the removal is a pending change "24" hours out
    And at that time no daily deposit limit applies

  Scenario: a new raise while one is pending replaces the pending change and restarts its day
    Given a pending change raising a daily deposit limit to "200"
    When the player sets it to "300"
    Then the pending change becomes "300" at a new time "24" hours from now
    And the earlier pending change is gone

  Scenario: a session limit below the brand's minimum is refused naming the minimum
    Given a brand whose minimum session limit is "60" minutes
    When the player sets a session limit of "30"
    Then it is refused naming the minimum

  Scenario: a session limit at the brand's minimum is accepted
    Given a brand whose minimum session limit is "60" minutes
    When the player sets a session limit of "60"
    Then it is accepted

  Scenario Outline: a session limit that is not a positive whole number is refused
    Given a brand whose minimum session limit is "60" minutes
    When the player sets a session limit of "<value>"
    Then it is refused as not a positive whole number

    Examples:
      | value |
      | 0     |
      | -5    |
      | abc   |

  Scenario: a weekly loss limit shows with the losses of the last week and refuses the bet that would cross it
    Given a weekly loss limit of "500"
    When the player reads their profile
    Then it shows the limit and the losses of the last "7" days
    And a bet that would take the week's losses past "500" is refused as "spending_limit" naming the remaining allowance
    And a bet within it is allowed

  Scenario: a limit change by the player is recorded in the history and published as a limit applied fact
    Given any limit of the player
    When they change it
    Then the history shows the change with the old and new value, who made it and when
    And one "rg_limit_applied" fact names the change
