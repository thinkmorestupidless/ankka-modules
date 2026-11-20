Feature: The module clients are generated from the service descriptions
  A module client's routes and the shapes of what each takes and answers are generated from the
  kept service descriptions, under the interface a behaviour already calls. A route is stated once,
  in its service, and the player SDK cannot fall behind it unseen.

  Background:
    Given the kept service descriptions of the services a player site calls

  Scenario Outline: a generated module client holds the routes its module client held when written by hand
    When the module client of the module "<module>" is generated
    Then it holds the same routes, each with the same service, the same callers and the same query parameters

    Examples:
      | module     |
      | core       |
      | casino     |
      | engagement |
      | support    |

  Scenario: the generated module clients answer the running brand as the module clients written by hand did
    Given the generated module clients
    When each call of each module client is made of brand "guyanabet"
    Then every call is answered as it was to the module client written by hand

  Scenario: generating the module clients changes no behaviour
    Given the deposit behaviour over the generated module client of the module "core"
    When a deposit is driven from the amount to succeeded
    Then the deposit behaviour takes the same steps

  Scenario: a service description that changed leaves the generated module clients out of date and the build says so
    Given a kept service description that changed after the module clients were generated
    When the player SDK is built
    Then the build fails, naming the generated module clients as out of date

  Scenario: an answer that does not fit its generated shape is refused as a malformed answer naming the service
    Given a service whose answer does not fit its service description
    When a generated module client reads the answer
    Then the answer is refused as a malformed answer, naming the service
    And no behaviour is given the answer

  Scenario: a selection naming a route that no service description holds fails the generation naming the route
    Given a selection that names a route no kept service description holds
    When the module clients are generated
    Then the generation fails, naming the route

  Scenario: a player route no selection names has no call in a module client and is refused by the site server
    Given a player route of the service "kyc" that no selection names
    When the module clients are generated
    Then no module client has a call of the route
    And the site server refuses a page's call of the route
