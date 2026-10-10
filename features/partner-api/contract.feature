Feature: A partner builds against a published, stable partner contract
  A partner fetches the partner contract, generates a client, pins a version and builds against it.
  A release that changes the published contract by accident fails before it reaches a partner; a
  deliberate breaking change is a new version served beside the old for the stated period. An
  internal change leaves the contract byte-identical.

  Scenario: a partner with credentials fetches the partner contract complete enough to generate a working client
    Given a partner with credentials
    When it fetches the partner contract
    Then it receives the published description, complete enough to generate a working client

  Scenario: a release that does not intend to change the partner contract serves an identical one or fails to build
    Given a release that does not intend to change the partner contract
    When it is built
    Then the partner contract it would serve is identical to the published one, or the build fails

  Scenario: a breaking change ships as a new version with the prior one served for at least six months
    Given a breaking change to the partner contract
    When it ships
    Then it is a new version
    And the prior version stays served for at least six months

  Scenario: a new field on a fact the views read leaves the published partner contract unchanged
    Given a new field on a fact the views read
    When it is released
    Then the published partner contract is unchanged
