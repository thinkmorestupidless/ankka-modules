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

  Scenario Outline: a document is kept for the lifetime the brand set for its kind, 24 hours when it set none
    Given the brand's lifetime for <kind> is <setting>
    When a <kind> is generated
    Then it is served until <hours> hours after it is ready and deleted then, with its record kept

    Examples:
      | kind              | setting       | hours |
      | compliance export | not set       | 24    |
      | compliance export | "720" hours   | 720   |
      | day-end export    | "2160" hours  | 2160  |

  Scenario: a document lifetime longer than 90 days is refused
    When an operator sets the brand's lifetime for compliance exports to "2161" hours
    Then the setting is refused naming its range, "1" to "2160" hours

  Scenario: a kept document that names an erased player is deleted before its lifetime ends, its record kept
    Given a compliance export kept for "720" hours that names a player
    When the player is erased
    Then the document is deleted
    And its record, holding no personal field, remains
