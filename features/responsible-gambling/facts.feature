Feature: The rest of the brand learns what changed about a player's protections
  The brand's engagement suppresses marketing to an excluded or cooling-off player, reporting
  counts exclusions and limits, the support agent sees the state, and the regulator's export has
  every case. The facts are the only way the other parts know.

  Background:
    Given a player of brand "goldvibe"

  Scenario Outline: a limit applied, lowered, raised by timer or removed is published as a limit applied fact
    Given a limit of the player
    When it is <change>
    Then an "rg_limit_applied" fact names the player, the limit type, the value or none, who caused it and when

    Examples:
      | change           |
      | applied          |
      | lowered          |
      | raised by timer  |
      | removed          |

  Scenario Outline: a break set or an exclusion applied is published as a case opened fact with its type, trigger and end
    Given the player is active
    When <happening>
    Then an "rg_case_opened" fact names the type, the trigger and the end if any

    Examples:
      | happening              |
      | a break is set         |
      | an exclusion is applied |

  Scenario Outline: a case that ends is published as a case closed fact with its reason
    Given an open case of the player
    When it ends as <reason>
    Then an "rg_case_closed" fact names the reason "<reason>"

    Examples:
      | reason  |
      | expired |
      | lifted  |
      | closed  |

  Scenario: a self-exclusion is published for the player and the brand's engagement sends them nothing but the confirmation
    Given the player is active
    When they exclude themselves
    Then a "player_self_excluded" fact is produced for the player
    And the brand's engagement sends them nothing but the confirmation

  Scenario: a national register refusal at sign-in is recorded as a self-exclusion case with a third party trigger and exported with the rest
    Given the player is refused at sign-in by the national register
    When the realm reports it
    Then a case of type self-exclusion with the trigger third party is recorded
    And it is exported with the rest

  Scenario: an erased player's protection facts keep their amounts, dates, types and statuses and lose any note
    Given the player is erased
    When their protection facts are read
    Then amounts, dates, types and statuses are readable
    And any note is erased
