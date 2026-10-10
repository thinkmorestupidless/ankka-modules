Feature: A partner pages a collection while the brand keeps writing
  A partner asks for sets, filters and orders them, and pages through more than fit in one answer
  while new records keep arriving. Every matching record arrives exactly once. A page larger than
  the maximum is capped and the maximum is readable. An empty match is an empty answer, not an
  error.

  Background:
    Given a partner holding a valid sign-in for brand "goldvibe"

  Scenario: paging to the end returns every matching record exactly once
    Given more matching records than one page
    When the partner pages to the end
    Then every matching record is returned exactly once

  Scenario: records written while a partner is part way through paging are neither served again nor skipped
    Given the partner part way through paging
    When new records are written
    Then records already served are not served again
    And older unseen records are not skipped

  Scenario: a query using a supported filter returns only matching records
    Given each supported filter
    When a query uses it
    Then only matching records are returned

  Scenario: a page size above the maximum is capped at the maximum, which the partner contract states
    Given a page size above the maximum
    When the query runs
    Then the page is capped at the maximum
    And the maximum is readable from the partner contract

  Scenario Outline: a page cursor that cannot be trusted is refused and nothing is leaked
    Given a page cursor that is <state>
    When it is presented
    Then the request is refused
    And nothing is leaked

    Examples:
      | state                     |
      | malformed                 |
      | expired                   |
      | issued to another partner |

  Scenario: a query with no match is answered empty
    Given a query with no match
    When it runs
    Then the answer is empty
