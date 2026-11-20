Feature: Operators of the shared payments are provisioned, invited and placed at a level
  An operator at the installation or holding level creates another operator with a role and a
  level, who receives an operator invitation, accepts it, sets a password and is thereafter an operator of
  exactly that level.

  Scenario: an operator created with a role and a level exists pending with its ancestors and is sent an operator invitation
    Given an operator at the installation level
    When they create an operator "ops@acme.example" with role "resource_admin" at the level of a company
    Then the operator exists pending with that role, that level and its ancestors
    And an operator invitation is sent

  Scenario: a pending operator who accepts the operator invitation and sets a password is active at their level
    Given a pending operator
    When they accept the operator invitation and set a password
    Then they are active
    And their sign-in carries their level

  Scenario Outline: an operator cannot create an operator above their own level or with more privilege than their own
    Given an operator at the level of one company
    When they create an operator <how>
    Then they are refused

    Examples:
      | how                                        |
      | at the level of a holding                  |
      | with a role of more privilege than their own |

  Scenario: an operator disabled at the sign-in reads as disabled in the shared payments
    Given an operator disabled at the sign-in
    When the sign-in is mirrored
    Then the operator reads as disabled

  Scenario: a brand's request sets an operator's roles for that brand
    Given an operator invited by the shared payments
    When the brand "goldvibe" asks for that operator to hold the roles "brand_support" and "vip_host"
    Then the operator holds those roles and the role naming the brand "goldvibe"
    And a role that did not exist in the realm is created there

  Scenario: a brand's request for a payments role or the platform administrator's role is refused
    Given an operator invited by the shared payments
    When the brand "goldvibe" asks for that operator to hold the role "platform_admin"
    Then the request is refused and the operator's roles are unchanged

  Scenario: a brand's request changes no role another brand's request gave
    Given an operator holding the role naming the brand "spinvibe"
    When the brand "goldvibe" asks for that operator to hold the role "brand_support"
    Then the operator still holds the role naming the brand "spinvibe"


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

  Scenario: a giving the realm refuses ends as failed and is not tried again
    Given an operator of "goldvibe" the realm no longer knows
    When "goldvibe" gives the operator the finance role
    Then the giving ends as failed, with the realm's reason
    And it is not tried again
