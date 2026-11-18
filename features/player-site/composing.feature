Feature: A site builder composes behaviours and supplies only the appearance
  The player SDK holds what a player site does and nothing of how it looks. A site builder binds each
  behaviour to their own components through the adapter, which tells a component the step the
  behaviour stands at and the actions that step allows. A behaviour reaches the brand only through
  module clients, so a module client can be replaced without a behaviour changing.

  Background:
    Given a player site of brand "guyanabet"

  Scenario: a behaviour run with no appearance takes every step it takes at a player site
    Given the deposit behaviour bound to no component
    When a deposit is driven from the amount to succeeded
    Then the deposit behaviour takes the same steps as at the reference site

  Scenario: two sites of different appearance run the same deposit
    Given two player sites of different appearance
    When a player deposits "5000" minor units at each
    Then the steps taken, the calls made of the brand and the outcome are the same at both

  Scenario: a component bound to a behaviour is told each step and the actions it allows
    Given a component bound to the deposit behaviour through the adapter
    When the deposit behaviour moves from the amount to choosing a terminal
    Then the component is told the step, its fields and the actions it allows

  Scenario: a site builder configures the brand, its languages and its preset amounts and no address of a service
    When a site builder configures a player site
    Then the configuration names the brand, the site's languages and its preset amounts
    And the configuration names no address of a service

  Scenario: replacing a module client with another of the same interface changes no behaviour
    Given the deposit behaviour over the module client of the module "core"
    When the module client is replaced by another that meets the same interface
    Then the deposit behaviour takes the same steps

  Scenario: a behaviour makes no call of the brand except through a module client
    When the deposit behaviour and the play behaviour are each driven through every step
    Then every call made of the brand was made through a module client

  Scenario: a site offers only the behaviours of the modules its brand runs
    Given a brand "solo" running the module "core" and not the module "casino"
    When a player site of brand "solo" is built
    Then the session behaviour and the deposit behaviour are offered
    And the play behaviour is not offered
