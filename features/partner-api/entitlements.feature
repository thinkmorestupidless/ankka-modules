Feature: An operator onboards, entitles, limits and revokes a partner
  An operator with the brand's role creates a partner entitlement: brand, enabled, the identifying
  fields the partner may read, the agreement that permits them, the requests per minute and the
  cost ceiling. The operator sees the partner's volume, errors and latency, sees it slowed past its
  rate with a retry signal while other partners are unaffected, withdraws a field and sees it stop
  within a minute, and disables the entitlement and sees requests refused. Every change is recorded
  with who made it.

  Scenario: an operator with the brand's role creates a partner entitlement and the partner reads that brand only, recorded with the operator and the time
    Given an operator whose roles name brand "goldvibe"
    When they create a partner entitlement for brand "goldvibe"
    Then the partner can read brand "goldvibe" and no other brand
    And the change is recorded with the operator and the time

  Scenario: an operator whose roles do not name the brand is refused a change to its partner entitlements
    Given an operator whose roles do not name brand "goldvibe"
    When they change a partner entitlement of brand "goldvibe"
    Then they are refused

  Scenario: a partner at its requests per minute has the next request refused with when to retry, and another partner is unaffected
    Given a partner at its requests per minute
    When it sends one more
    Then it is refused with a signal that says when to retry
    And another partner's latency is unchanged

  Scenario Outline: a query above the depth, complexity or time ceiling is refused or stopped before it burdens the store
    Given a query above the <ceiling> ceiling
    When it runs
    Then it is refused or stopped before it burdens the store

    Examples:
      | ceiling    |
      | depth      |
      | complexity |
      | time       |

  Scenario: a partner entitled to a subset of identifying fields is served nothing outside it, with the partner contract unchanged
    Given a partner entitled to a subset of identifying fields
    When it queries players
    Then fields outside the entitlement are absent from the answer, not empty
    And the partner contract is unchanged

  Scenario: a field withdrawn from an entitlement stops arriving within a minute with no release, credential or contract version
    Given a field an operator withdrew from a partner entitlement
    When the partner queries within one minute
    Then the field no longer arrives
    And no release, no new credential and no new partner contract version was needed

  Scenario: an entitlement with identifying fields but no recorded agreement serves no identifying field
    Given a partner entitlement with identifying fields but no recorded agreement, lawful basis or date
    When the partner queries players
    Then no identifying field is served

  Scenario: an operator sees each partner's request volume, error rate and latency by partner
    Given partners querying
    When an operator looks
    Then each partner's request volume, error rate and latency are visible by partner
