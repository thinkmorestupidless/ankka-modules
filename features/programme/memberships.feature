Feature: An affiliate works several brands from one account
  An affiliate who already sends traffic to one brand applies to a second. They sign in once, see
  both brands side by side, and every page of the portal answers for one brand or for all of them.
  Each brand decides on its own whether to approve them, and on what plan.

  Scenario: applying to a second brand leaves the first membership unchanged and shows the second as pending
    Given an affiliate with an active membership at brand "A"
    When they apply to brand "B"
    Then brand "B" shows one pending application
    And the membership at brand "A" is unchanged
    And the affiliate's portal lists brand "A" as active and brand "B" as pending

  Scenario: a manager for a brand approves an application on that brand's default plan and a manager for another brand cannot see it
    Given a pending application at brand "B"
    When a manager for brand "B" approves it on the default plan of brand "B"
    Then the membership is active on that plan
    And the affiliate is told
    And a manager for brand "A" only can neither see nor act on it

  Scenario: revenue read for one brand folds only that brand's facts
    Given active memberships at brands "A" and "B" with facts at each
    When the affiliate reads revenue for brand "A"
    Then only the facts of brand "A" are folded

  Scenario: revenue read with no brand filter lists each brand separately with totals per currency
    Given active memberships at brands "A" and "B" with facts at each
    When the affiliate reads revenue with no brand filter
    Then each brand's figures are listed separately
    And totals are given per currency and never summed across currencies

  Scenario: a suspended membership still records facts and writes no new payable fact while another brand is unaffected
    Given an affiliate suspended at brand "A" and active at brand "B"
    When facts arrive from brand "A" for players attributed to them
    Then the facts are recorded in the ledger
    And no new payable fact is written while the membership is suspended
    And brand "B" is unaffected

  Scenario: a rejected applicant may apply again
    Given an affiliate rejected at brand "B"
    When they apply to brand "B" again
    Then the application is accepted

  Scenario: an application from an email with a pending or active membership at that brand is refused as a duplicate
    Given an applicant whose email already has a pending or active membership at brand "B"
    When they apply to brand "B"
    Then the application is refused as a duplicate
