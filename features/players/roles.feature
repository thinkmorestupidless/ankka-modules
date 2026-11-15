Feature: A brand keeps its roles and what each may do
  The brand's players part keeps each role: its name, what it is for, and the permissions it
  carries. Every part of the brand reads them from it, and a change is in force within seconds.

  Scenario: a new brand starts with the default roles and their default permissions
    Given a brand that has just been applied
    When an operator reads the brand's roles
    Then the roles "brand_support", "brand_finance", "brand_compliance" and "brand_admin" are listed
    And each carries its default permissions

  Scenario: a role's change is in force in every part of the brand within fifteen seconds and no part is deployed again
    Given a finance operator who is admitted to making an adjustment
    When an admin operator removes the permission to make an adjustment from the role "brand_finance"
    Then within "15" seconds the finance operator's adjustment is refused, naming the permission
    And no part of the brand was deployed again

  Scenario: every change to a role is recorded with who made it
    Given an admin operator of a brand
    When the operator changes the permissions of a role
    Then an audit record names the operator, the role and the permissions before and after

  Scenario: a role naming a permission the contract does not know is refused
    Given an admin operator of a brand
    When the operator creates a role carrying a permission that does not exist
    Then the role is refused, naming the permission

  Scenario: the last role that may change roles cannot lose that permission
    Given a brand whose only role carrying the permission to change roles is "brand_admin"
    When an admin operator removes that permission from "brand_admin"
    Then the change is refused, because nobody could change roles after it

  Scenario: a default role is reset to its default permissions and cannot be deleted
    Given a brand whose role "brand_support" was changed
    When an admin operator resets the role "brand_support"
    Then it carries its default permissions
    And deleting the role "brand_support" is refused

  Scenario: a role the brand created is deleted and no longer admits anyone
    Given a role "vip_host" the brand created, held by an operator
    When an admin operator deletes the role "vip_host"
    Then within "15" seconds that operator is refused what only "vip_host" admitted

  Scenario: a platform administrator holds every permission whatever the brand's roles say
    Given a brand whose roles were changed
    When an operator holding the role "platform_admin" asks for any action
    Then they are admitted
    And the role "platform_admin" cannot be changed by the brand

  Scenario: a part that has not yet read the brand's roles answers that it is unavailable
    Given a part of the brand that has just started and has not read the brand's roles
    When an operator asks it for an action
    Then the operator is told the part is unavailable, and is neither admitted nor refused

  Scenario: a part that cannot reach the brand's roles keeps using the last it read
    Given a part of the brand that has read the brand's roles
    When the players part cannot be reached
    Then operators are admitted and refused by the roles the part last read

  Scenario: giving a role is refused when the giver lacks a permission the role carries
    Given an operator who may give roles and does not hold the permission to make an adjustment
    When the operator gives the role "brand_finance" to another operator
    Then the brand refuses, naming the permission, and the shared payments are not asked
    And the other operator's roles are unchanged

  Scenario: giving a role the giver holds every permission of asks the shared payments to set it
    Given an admin operator of a brand
    When the operator gives the role "brand_support" to another operator
    Then the shared payments are asked to set that operator's roles for the brand
    And an audit record names both operators and the role
