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
    When they claim another offer allowing one open reward
    Then they are refused as "ACTIVE_BONUS_CONFLICT"

  Scenario: an offer allowing two open rewards is claimed beside one the player holds
    Given a player with an active reward
    When they claim an offer allowing two open rewards
    Then the claim proceeds

  Scenario: an offer setting no maximum of open rewards is claimed beside any the player holds
    Given a player with two active rewards
    When they claim an offer that sets no maximum of open rewards
    Then the claim proceeds

  Scenario: a no-deposit offer claimed while the player's duplicate check is in progress is refused for now
    Given a player registered five minutes ago whose duplicate check is in progress
    When they claim an offer with no deposit required
    Then they are refused as "ELIGIBILITY_CHECK_UNAVAILABLE"

  Scenario: a duplicate check still in progress ten minutes after registration no longer refuses
    Given a player registered eleven minutes ago whose duplicate check never ended
    When they claim an offer with no deposit required
    Then the duplicate check is not what refuses them

  Scenario: a no-deposit offer claimed by a player with a weak duplicate signal is refused
    Given a player whose no-deposit offers are blocked by a weak duplicate signal
    When they claim an offer with no deposit required
    Then they are refused as "NO_DEPOSIT_OFFER_BLOCKED"

  Scenario: a deposit offer is not refused for a duplicate check in progress or a weak duplicate signal
    Given a player whose duplicate check is in progress and whose no-deposit offers are blocked
    When they claim an offer requiring a deposit
    Then neither refuses them

  Scenario: an award by the brand's engagement during the duplicate check is answered to try again later
    Given a player whose duplicate check is in progress
    When the brand's engagement awards them an offer with no deposit required
    Then it is answered as unavailable for now, to try again later

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

  Scenario: an operator's award proceeds past every rule but the duplicate checks and the open reward maximum
    Given a player who would fail any rule but the duplicate checks and the open reward maximum
    When an operator awards them an offer by hand
    Then the award proceeds, because an operator's award applies only those four rules

  Scenario: an offer code verified when the offer was targeted is not demanded again when the deposit confirms
    Given an offer code verified when the offer was targeted at the player
    When the deposit confirms
    Then the code is not demanded again
