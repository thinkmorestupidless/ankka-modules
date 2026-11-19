Feature: A service describes its own player routes and is held to what it says
  A service a player site calls gives a service description of its player routes, derived from the
  routes it serves and from each route description. The service description is kept in the
  repository, and the service, its tests and the recordings are held to it.

  Scenario Outline: a service a player site calls gives a service description of every player route and of no other route
    Given the service "<service>"
    When its service description is written
    Then it holds every player route of the service
    And it holds no route of an operator, of another service or of a provider

    Examples:
      | service              |
      | players              |
      | wallet               |
      | ingest               |
      | game-provider        |
      | kyc                  |
      | responsible-gambling |
      | rewards              |
      | engagement           |
      | support-agent        |

  Scenario: a route description states what the route takes, what it answers, every refusal and who may call it
    Given a player route of the service "players"
    When its route description is read
    Then it states a summary, what the route takes and what it answers
    And it states every refusal the route makes and every query parameter it reads
    And it states whether the caller is a visitor, a signed-in player or a player presenting a cashier ticket

  Scenario: a service description with an incomplete player route is refused naming the route
    Given a player route whose route description does not state what it answers
    When the service description is written
    Then it is refused, naming the route and what its route description lacks

  Scenario: a route that changes without its kept service description fails the build naming the route
    Given the kept service description of the service "wallet"
    When a player route of the service answers a field the kept service description does not hold
    Then the build fails, naming the route
    And the kept service description is unchanged

  Scenario: a route answered as a stream says so in its route description
    Given the push connection of the service "engagement"
    When its route description is read
    Then it states that the answer is a stream

  Scenario: describing a route changes nothing the route answers
    Given a player route of the service "players" with no route description
    When the route is given a route description
    Then the route answers every request as it did before

  Scenario: every recorded call of a player route is allowed by its route description
    Given a recording that holds a call of a player route
    When the description check runs
    Then the recorded answer is one the route description allows

  Scenario: a route description that omits a refusal a recording shows fails the description check
    Given a recording in which a player route makes a refusal its route description omits
    When the description check runs
    Then it fails, naming the recording, the route and the refusal

  Scenario: a test that causes an answer the service description does not allow fails
    Given the tests of the service "responsible-gambling" held to its service description
    When a test causes an answer the service description does not allow
    Then the tests fail, naming the route and the answer

  Scenario: the player routes no recording calls are listed and the list is kept
    Given a player route that no recording calls
    When the description check runs
    Then the route is listed as having no recording
    And the description check fails when the routes listed differ from the kept list of them

  Scenario: a recorded answer is checked as the parity test reads it
    Given a recording whose answer differs from the rebuilt service by a difference a spec names
    When the description check runs
    Then the recorded answer is checked as the service's parity test reads it
    And the difference is not a failure
