Feature: A brand runs its core and the modules it is entitled to
  A brand project holds the core every brand runs and the services of the modules the vendor has
  entitled it to, no more. Modules are described by manifests, released by one tag, enabled by
  eitheror staff from the vendor's console, and applied in the brand by the vendor's agent.

  Background:
    Given an installation whose shared payments, programme and audit are running
    And a brand "goldvibe" provisioned by the vendor with an entitlement to the module "core"

  Scenario: a brand runs the core alone until a module is enabled
    When the agent applies the brand's entitlements
    Then brand "goldvibe" runs the services of the module "core" and no other
    And a player can register, deposit and withdraw at brand "goldvibe"
    And no game route and no market route is served at brand "goldvibe"

  Scenario: enabling a module adds its services and grants to the brand and nothing to any other brand
    Given a brand "spinvibe" running the module "core"
    When eitheror staff enable the module "casino" for brand "goldvibe"
    Then brand "goldvibe" runs the services of the module "casino" beside the core
    And what the module "casino" allows the core and the shared payments, programme and audit is in force in brand "goldvibe"
    And brand "spinvibe" is unchanged

  Scenario: a brand entitled to the prediction market and not the casino serves markets and no games
    When eitheror staff enable the module "predictions" for brand "goldvibe"
    Then brand "goldvibe" serves market routes
    And no game route is served at brand "goldvibe"

  Scenario: disabling a module removes its services and keeps its data for the module's retention
    Given brand "goldvibe" running the module "casino"
    When eitheror staff disable the module "casino" for brand "goldvibe"
    Then the services of the module "casino" are removed from brand "goldvibe"
    And the data of the module "casino" is kept for the retention its manifest names

  Scenario: a module manifest naming a service, a topic or a grant the contract does not list is refused naming the entry
    When the modules tool validates a manifest that names a service the contract does not list
    Then the manifest is refused, naming the unknown entry

  Scenario: a module manifest carrying a value where a key is expected is refused naming the key
    When the modules tool validates a manifest that carries a value for a configuration key
    Then the manifest is refused, naming the key

  Scenario: one tag publishes every module at the tag's version and moves no channel
    When the release job runs for a tag
    Then every module is published to the vendor at the tag's version
    And no channel is moved

  Scenario: a brand's values carry no version, no service and no grant
    When the modules tool validates a values file that names a version
    Then the values file is refused, naming the entry

  Scenario: a brand whose values lack a key an enabled module names runs nothing of that module until the key is set
    Given the values of brand "goldvibe" lack a key the module "casino" names
    When eitheror staff enable the module "casino" for brand "goldvibe"
    Then nothing of the module "casino" runs at brand "goldvibe"
    And the brand's module entity names the missing key

  Scenario: a real-money brand is refused by the agent on an installation that is not fit for it
    Given an installation that is not fit for real money
    And the values of brand "goldvibe" mark it for real money
    When the agent applies the brand's entitlements
    Then nothing is applied, naming the condition the installation fails

  Scenario: a brand operator sees their brand's modules and cannot enable one
    Given an operator of brand "goldvibe"
    When the operator opens the modules page of the backoffice
    Then the operator sees each module of brand "goldvibe" with its version, channel and the state of its services
    And the operator cannot enable a module

  Scenario: eitheror staff enable a module from the vendor's console and see no player of any brand
    When eitheror staff enable the module "casino" for brand "goldvibe" from the vendor's console
    Then the module "casino" is enabled for brand "goldvibe"
    And the vendor's console shows no player, wallet or configured value of any brand

  Scenario: a module's every change is a fact on the brand's platform topic
    When eitheror staff enable the module "casino" for brand "goldvibe"
    Then a fact naming the module "casino", the change, the version and the actor is written on the brand's platform modules topic
