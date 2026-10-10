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
