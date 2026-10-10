Feature: An operator sets a sub-brand up to take deposits
  An operator creates a holding, a company under it and a sub-brand under that, registers a payment
  provider's credential or a terminal, generates a cashier key for the brand's frontend, writes a
  routing tree and deploys it, simulates a deposit against it, and can roll the tree back. A
  sub-brand with no tree of its own takes its company's, then its holding's.

  Scenario: a holding, a company and a sub-brand are created each recording its parent
    Given an operator at the installation level
    When they create a holding "Acme Holdings", a company under it and a sub-brand "Acme Casino" of type "Casino" naming brand "acme"
    Then each exists with status "Active"
    And each child records its parent
    And the sub-brand is listed under the company

  Scenario: a credential registered for a payment provider is listed without its secrets and brings a terminal of the same name that takes deposits only
    Given a sub-brand
    When the operator registers a credential for payment provider "dreamzpay" named "main" with its secrets
    Then the credential is listed without its secrets and is active
    And a terminal named "main" exists that takes deposits and never withdrawals

  Scenario: a terminal created for a payment provider exists inactive, takes deposits, and can be changed or deleted
    Given a sub-brand
    When the operator creates a terminal for payment provider "dreamzpay" with scheme "CARD", a name, regions and properties
    Then the terminal exists inactive
    And it takes deposits, and withdrawals only when the request said so
    And it can be activated, disabled, given new properties or a new capability, or deleted

  Scenario: a generated cashier key is shown raw once and listed by id and name thereafter
    Given a sub-brand
    When the operator generates a cashier key named "frontend"
    Then the raw key is shown once
    And the sub-brand lists the key by id and name without the raw value
    And a cashier session created with that key resolves to the sub-brand

  Scenario: deploying a draft routing tree makes it live version one, and a later deploy is version two with a rollback to one possible
    Given a routing tree saved as a draft at the sub-brand level
    When the operator deploys it
    Then it is live as version "1" with a deployment time
    And the history lists it
    And a later deploy becomes version "2", from which a rollback to version "1" is possible

  Scenario: a sub-brand with no routing tree of its own is governed by its holding's, and the answer says which level it came from
    Given a routing tree at the holding level and none at the company or sub-brand level
    When a cashier session is created for a sub-brand under that holding
    Then the holding's routing tree governs it
    And the answer says which level the tree was resolved from

  Scenario: simulating a deposit against a routing tree names the terminals it reaches or that nothing matched
    Given a routing tree
    When the operator simulates a deposit of "25000" minor units of "EUR" from "DE" with a deposit count of "0"
    Then the answer names the terminals the tree reaches, or that nothing matched

  Scenario: an operator of one company is refused a sub-brand of another company as outside their level
    Given an operator at the level of one company
    When they read or change a sub-brand of another company
    Then they are refused as outside their level

  Scenario: an operator of one company is served a sub-brand of their own company
    Given an operator at the level of one company
    When they read a sub-brand of their own company
    Then they are served
