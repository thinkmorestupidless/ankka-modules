Feature: An operator of a brand creates and manages the brand's access keys
  An operator holding the access keys permission on a brand creates keys for the public API from the
  backoffice: a name, an optional expiry, the identifying fields the key may read (none by default)
  and its requests per minute (600 by default). The secret is shown once. The operator lists the
  brand's keys, disables, enables, changes and deletes them, and every change is recorded with who
  made it. There is no partner: a key is the brand's, for whatever the brand uses it for.

  Scenario: an operator with the access keys permission creates a key and is shown its secret once
    Given an operator holding the access keys permission on brand "goldvibe"
    When they create an access key named "bi"
    Then they are shown the key's secret
    And the access key can read brand "goldvibe"

  Scenario: a key's secret is never shown again after it is created
    Given an access key of brand "goldvibe" created yesterday
    When an operator lists or reads the brand's access keys
    Then no answer carries the key's secret

  Scenario Outline: an operator without the access keys permission, or whose roles do not name the brand, cannot see or change the brand's keys
    Given an operator <who>
    When they list, create or change an access key of brand "goldvibe"
    Then they are refused

    Examples:
      | who                                                  |
      | whose roles name brand "goldvibe" without the access keys permission |
      | holding the access keys permission on brand "spinvibe" only           |

  Scenario: the brand's keys are listed with their name, the start of the key, their settings, who created them, when, and when each was last used
    Given brand "goldvibe" holds two access keys
    When an operator holding the access keys permission lists them
    Then each is shown with its name, the start of the key, its fields, its rate, its expiry, who created it, when, and when it was last used

  Scenario: a disabled key is refused from the next request and is accepted again once enabled
    Given an access key presented at brand "goldvibe"
    When an operator disables it
    Then the next request with it is refused
    And once the operator enables it again, the next request is served

  Scenario: a deleted key is refused from the next request and can never be enabled again
    Given an access key presented at brand "goldvibe"
    When an operator deletes it
    Then the next request with it is refused
    And it cannot be enabled again

  Scenario: a key past its expiry is refused
    Given an access key whose expiry has passed
    When a request presents it
    Then it is refused as expired

  Scenario: a change to a key's fields or rate applies from the next request
    Given an access key presented at brand "goldvibe"
    When an operator changes the identifying fields it may read or its requests per minute
    Then the next request with it is served under the change

  Scenario: every change to a key is an audit record naming the operator, the key and what changed, and never the secret
    Given an operator holding the access keys permission on brand "goldvibe"
    When they create, change, disable, enable or delete an access key
    Then an audit record names the operator, the key, what changed and the time
    And no audit record carries the key's secret
