Feature: A deposit or a game launch is allowed or refused by the KYC policy
  The part that starts a deposit asks whether this player may deposit this amount; the part that
  opens a game session asks whether this player may play. The answer comes from the policy's gates,
  the player's statuses, and the player's lifetime deposits.

  Background:
    Given a player of brand "goldvibe"

  Scenario Outline: a brand whose deposit gate is verified refuses a deposit by an unverified, pending or rejected player with the status's own words
    Given the brand's deposit gate is verified and the player's KYC status is <status>
    When the deposit check is asked
    Then it refuses with "DEPOSIT_BLOCKED_KYC" and "<words>"

    Examples:
      | status     | words                                                          |
      | unverified | Please verify your identity before depositing                  |
      | pending    | Your identity documents are under review                       |
      | rejected   | Your identity documents were not accepted — please re-submit   |

  Scenario: a deposit that would take an unverified player over the deposit threshold is refused and flags them for review
    Given the brand's deposit gate is none with a deposit threshold of "200000", and the player is unverified with lifetime deposits of "190000"
    When a deposit of "20000" is checked
    Then it refuses with "DEPOSIT_BLOCKED_KYC_THRESHOLD"
    And the player is flagged for review with reason "threshold"
    And a "player_kyc_review_required" fact is published

  Scenario: a deposit that keeps an unverified player under the deposit threshold is allowed
    Given the brand's deposit gate is none with a deposit threshold of "200000", and the player is unverified with lifetime deposits of "190000"
    When a deposit of "5000" is checked
    Then it is allowed

  Scenario: a deposit that would take a player whose source of funds is not cleared over the source of funds threshold is refused and flags them
    Given a source of funds threshold of "1000000", and the player's source of funds not cleared with lifetime deposits of "990000"
    When a deposit of "20000" is checked
    Then it refuses with "DEPOSIT_BLOCKED_SOF"
    And the player is flagged for review with reason "source of funds"

  Scenario: a deposit over the source of funds threshold by a player whose source of funds is cleared is allowed
    Given a source of funds threshold of "1000000", and the player's source of funds cleared with lifetime deposits of "990000"
    When a deposit of "20000" is checked
    Then it is allowed

  Scenario: a brand whose play gate is verified refuses play by an unverified player with the status's words
    Given the brand's play gate is verified and the player is unverified
    When the play check is asked
    Then it refuses with "PLAY_BLOCKED_KYC" and the status's own words

  Scenario Outline: play is allowed for a verified player or on a brand whose play gate is none
    Given <situation>
    When the play check is asked
    Then it is allowed

    Examples:
      | situation                                                   |
      | the brand's play gate is verified and the player is verified |
      | the brand's play gate is none and the player is unverified  |

  Scenario: a deposit confirmed by the wallet grows the player's lifetime deposits for the next check
    Given the player's lifetime deposits of "190000"
    When a "deposit_confirmed" fact of "5000" is read
    Then the player's lifetime deposits are "195000"
    And a later check uses the new total

  Scenario: a tightened policy flags each unverified player over the new threshold for review once
    Given a brand whose KYC policy tightens while unverified players have lifetime deposits over the new deposit threshold
    When the policy is saved
    Then each such player is flagged for review once

  Scenario: a deposit check in a currency other than the KYC currency is refused while the brand has a threshold
    Given the brand's KYC currency is "GBP" with a deposit threshold of "200000"
    When a deposit of "5000" in "EUR" is checked
    Then it refuses with "DEPOSIT_BLOCKED_KYC_CURRENCY"

  Scenario: a deposit check in any currency is answered by the gates alone when the brand has no threshold
    Given the brand has no deposit threshold and no source of funds threshold, and its deposit gate is none
    When a deposit of "5000" in "EUR" is checked
    Then it is allowed

  Scenario: only deposits in the KYC currency count toward the player's lifetime deposits
    Given the brand's KYC currency is "GBP" and the player's lifetime deposits of "190000"
    When a "deposit_confirmed" fact of "5000" in "EUR" is read
    Then the player's lifetime deposits are still "190000"

  Scenario Outline: a confirmed deposit that alone exceeds the deposit threshold flags a player not verified for review and suspends no one
    Given the brand's deposit threshold of "200000" and the player's KYC status is <status>
    When a "deposit_confirmed" fact of "250000" is read
    Then the player is flagged for review with reason "large deposit"
    And a "player_kyc_review_required" fact is published
    And the player is not suspended

    Examples:
      | status     |
      | unverified |
      | pending    |
      | rejected   |

  Scenario: a confirmed deposit that alone exceeds the deposit threshold does not flag a verified player
    Given the brand's deposit threshold of "200000" and the player is verified
    When a "deposit_confirmed" fact of "250000" is read
    Then the player is not flagged
