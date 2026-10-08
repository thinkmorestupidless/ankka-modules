Feature: A brand's single-brand affiliates move into the programme
  Today's single-brand affiliates, their plans, links, bonus codes, invite links, creatives, notes,
  fraud flags, audit and the whole commission ledger become the programme's, with that brand as
  their one membership, and nothing owed changes.

  Scenario: each imported account becomes one account with one membership at the brand on its plan
    Given the affiliate accounts of brand "spinvibe"
    When they are imported
    Then each becomes one account with one active, pending, suspended or rejected membership at brand "spinvibe" on its plan
    And each keeps its parent, override, minimum payout threshold and postback address
    And each affiliate's sign-in is unchanged

  Scenario: the imported ledger keeps every fact and payout and folds to the original's revenue
    Given the commission ledger of brand "spinvibe"
    When it is imported
    Then every fact keeps its type, amount, currency, occurred-at, recorded-at, source affiliate and the payout that claimed it
    And every payout keeps its status, figures and approval
    And a fold of the imported facts equals the original's revenue for every affiliate and period

  Scenario: imported links and bonus codes keep their legacy tag for the transition period and gain a signed tag
    Given the links and bonus codes of brand "spinvibe"
    When they are imported
    Then each keeps its legacy tag for the transition period
    And each gains a tag signed by the programme

  Scenario: a fact for a player attributed before the import is attributed because the import carried the pseudonym and attribution
    Given the import of brand "spinvibe"
    When a fact arrives from brand "spinvibe" for a player attributed before the import
    Then it is attributed, because the import carried the player's pseudonym and attribution

  Scenario: running the import again duplicates nothing
    Given the import of brand "spinvibe" ran
    When it is run again
    Then nothing is duplicated
