Feature: A brand's administrators decide what each role may do
  Every action needs one permission, and a role is a bag of permissions the brand keeps. A brand
  administrator creates roles, changes what they carry and gives them to operators, from the
  backoffice, with no deployment.

  Scenario: a brand administrator creates a role from permissions and it can be given to an operator
    Given an admin operator of a brand
    When the operator creates the role "vip_host" carrying the permissions to read a player and to send an inbox item
    And gives the role "vip_host" to another operator of the brand
    Then that operator is offered reading a player and sending an inbox item
    And is offered no other action

  Scenario: a role's changed permissions change what its operators are offered without their signing in again
    Given a support operator signed in to the backoffice
    When an admin operator adds the permission to make an adjustment to the role "brand_support"
    Then within "15" seconds the support operator is offered making an adjustment
    And the support operator has not signed in again

  Scenario: a giving ends as asked when the realm stops answering part-way through it
    Given an operator holding the support role in "goldvibe"
    And the realm stops answering part-way through the next giving
    When "goldvibe" gives the operator the finance role instead
    Then once the realm answers again the operator holds the finance role in "goldvibe" and not the support role
    And nobody has asked again

  Scenario: two brands giving one operator roles at the same moment each end as their own brand asked
    Given an operator whose roles name the brands "goldvibe" and "spinvibe"
    When "goldvibe" gives the operator the finance role at the same moment as "spinvibe" gives the operator the compliance role
    Then the operator holds the finance role in "goldvibe" only
    And the compliance role in "spinvibe" only

  Scenario: a brand's later giving replaces its earlier one, even before the earlier is in force
    Given "goldvibe" has given an operator the finance role, not yet in force
    When "goldvibe" gives the operator the support role
    Then the operator ends holding the support role in "goldvibe" and not the finance role

  Scenario: a giving not yet in force is read as asked and as not yet in force
    Given "goldvibe" has given an operator the finance role, not yet in force
    When an admin operator of "goldvibe" reads the operator's roles
    Then the finance role is shown as asked and not yet in force

  Scenario: a role held with no brand in its name is carried over to every brand the operator works in, then removed
    Given an operator whose roles name the brands "goldvibe" and "spinvibe"
    And the operator holds the finance role with no brand in its name
    When the brands "goldvibe" and "spinvibe" are applied
    Then the operator holds the finance role in "goldvibe" and in "spinvibe"
    And no longer holds the finance role with no brand in its name

  Scenario: a giving the realm refuses ends as failed, with the realm's reason shown to the brand
    Given an operator of "goldvibe" the realm no longer knows
    When "goldvibe" gives the operator the finance role
    Then the giving is shown to "goldvibe" as failed, with the realm's reason
    And it is not tried again

  Scenario Outline: a brand gives the access keys and markets roles as it gives its other roles, in that brand only
    Given an admin operator of "goldvibe"
    When the operator gives another operator of "goldvibe" the role "<role>"
    Then that operator holds the role "<role>" in "goldvibe" only

    Examples:
      | role     |
      | api_keys |
      | markets  |
