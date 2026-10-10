Feature: Compliance exports cases and decisions for a regulator
  A compliance officer, asked by the regulator for the brand's responsible gambling cases or KYC
  decisions over a period, generates the export and hands it over with its reference number.

  Background:
    Given brand "goldvibe"

  Scenario: an export of cases holds one row per case with the player, type, opened, closed, outcome and reason
    Given cases opened and closed in a period
    When compliance exports them
    Then one row per case holds the player, type, opened, closed, outcome and reason, from the "rg_case_opened", "rg_case_closed", "rg_limit_applied" and "player_self_excluded" facts

  Scenario: an export of KYC decisions holds one row per decision with the player, submitted, decided, outcome and reason
    Given KYC decisions in a period
    When compliance exports them
    Then one row per decision holds the player, submitted, decided, outcome and reason, from the "player_kyc_submitted", "player_kyc_verified" and "player_kyc_rejected" facts

  Scenario: a compliance export carries a reference number, is served as an attachment, and is deleted after a day with its record kept
    Given a compliance export
    When it is generated
    Then it carries a reference number, the period and the generation time
    And it is served as an attachment
    And it is deleted after "24" hours with its record kept
