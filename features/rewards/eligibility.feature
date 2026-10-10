Feature: Eligibility is decided once, with the same reasons on every path
  Every path to a reward, a selection, an offer code, a confirming deposit, a targeting or an
  operator's award, asks the same eligibility rules. A player declined is told the one reason the
  original names.

  Background:
    Given brand "goldvibe"

  Scenario: a player flagged as a suspected duplicate is refused on any path
    Given a player flagged as a suspected duplicate
    When they claim an offer on any path
    Then they are refused as "SUSPECTED_DUPLICATE"

  Scenario: the duplicate rule is skipped on a brand that turned duplicate detection off
    Given a player flagged as a suspected duplicate at a brand that turned duplicate detection off
    When they claim an offer
    Then the duplicate rule is skipped

  Scenario: a player with an active reward claiming another offer is refused
    Given a player with an active reward
    When they claim another offer
    Then they are refused as "ACTIVE_BONUS_CONFLICT"

  Scenario: an offer that conflicts with one the player holds open is refused
    Given an offer that conflicts with an offer the player holds open
    When the player claims it
    Then they are refused as "CONFLICTING_GRANT"

  Scenario: an offer claimed the maximum times per player is refused on the next claim
    Given an offer the player has claimed the maximum times per player
    When they claim it again
    Then they are refused as "MAX_CLAIMS_REACHED"

  Scenario: an offer excluded by one the player already claimed is refused
    Given an offer excluded by one the player already claimed
    When the player claims it
    Then they are refused as "EXCLUDED_BY_PRIOR_OFFER"

  Scenario Outline: a claim that fails one rule is refused with that rule's reason
    Given <situation>
    When the player claims the offer
    Then they are refused as "<reason>"

    Examples:
      | situation                                                           | reason                  |
      | an offer code the offer does not carry                              | INVALID_CODE            |
      | a deposit below the offer's minimum deposit                         | BELOW_MIN_DEPOSIT       |
      | a deposit that is not the offer's deposit number                    | DEPOSIT_NUMBER_MISMATCH |
      | a claim after the claim window counted from registration            | CLAIM_WINDOW_EXPIRED    |
      | a player outside the offer's segment                                | SEGMENT_MISMATCH        |
      | a player below the offer's tier floor                               | VIP_TIER_TOO_LOW        |

  Scenario: an operator's award proceeds past every rule but the duplicate flag and the active reward conflict
    Given a player who would fail any rule but the duplicate flag and the active reward conflict
    When an operator awards them an offer by hand
    Then the award proceeds, because an operator's award applies only those two rules

  Scenario: an offer code verified when the offer was targeted is not demanded again when the deposit confirms
    Given an offer code verified when the offer was targeted at the player
    When the deposit confirms
    Then the code is not demanded again
