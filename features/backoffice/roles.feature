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
