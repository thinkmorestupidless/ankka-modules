Feature: A period closes and affiliates are paid
  A manager closes a period for a brand. Every unclaimed payable fact that occurred on or before
  the period's end is claimed by that affiliate's payout for the period, in that currency; the
  payout is approved, rejected or paid; and finance exports the approved ones.

  Scenario: a close claims every unclaimed payable fact up to the period's end for a pending payout
    Given unclaimed payable facts for an affiliate at brand "A" in "EUR"
    When a manager closes the period for brand "A"
    Then every unclaimed payable fact that occurred on or before the period's end is stamped with the payout for that affiliate at brand "A" in "EUR" for the period
    And the payout's total is the fold of everything stamped with it
    And the payout is pending

  Scenario: a close below the affiliate's minimum payout threshold claims nothing and carries the facts forward
    Given an affiliate whose claimed total is below their minimum payout threshold
    When a manager closes the period for brand "A"
    Then nothing is stamped
    And no payout is written
    And the facts carry forward to the next close

  Scenario: approving a pending payout records the override figure the manager chose
    Given a pending payout
    When a manager approves it, optionally overriding the override figure
    Then the payout is approved with the figure recorded

  Scenario: a late fact swept into an approved payout reverts it to pending and supersedes the approval
    Given an approved payout
    When a late fact is swept into it by a later close
    Then the payout reverts to pending
    And the previous approval is recorded as superseded

  Scenario: a late fact for a paid payout leaves it unchanged and is claimed by the next close
    Given a paid payout
    When a late fact arrives that would have belonged to it
    Then the payout is unchanged
    And the fact is claimed by the next close, dated when it occurred

  Scenario: facts in two currencies close as two payouts
    Given an affiliate with facts in "EUR" and in "USD" at brand "A"
    When a manager closes the period for brand "A"
    Then two payouts are written, one per currency
    And the export lists both

  Scenario: an export of approved payouts lists every figure and is audited as a read of payment details
    Given approved payouts for a period
    When finance exports them
    Then the file lists affiliate, display name, email, payment details, brand, currency, period, CPA, RevShare, override, adjustments, total, who approved and when
    And the export is audited as a read of payment details

  Scenario Outline: a payout transition the status machine does not allow is refused naming the current status
    Given a "<status>" payout
    When it is moved to "<target>"
    Then it is refused, naming "<status>"

    Examples:
      | status   | target  |
      | paid     | pending |
      | rejected | paid    |
