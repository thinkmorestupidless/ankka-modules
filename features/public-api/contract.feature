Feature: A caller builds against a published, stable public contract
  The public contract is served without a key, with a reference page for people. Every answer
  matches it. An accidental change fails the build; a breaking change is a new version served beside
  the old for six months.

  Scenario: the public contract is served without a key, complete enough to generate a working client
    Given a caller with no access key
    When it fetches the public contract
    Then it receives every operation, parameter, answer and refusal, and the maximum page size
    And a client generated from it reads every operation with an access key

  Scenario: every answer, success or refusal, matches the public contract
    Given every operation of the public contract
    When each is asked for with valid and invalid requests
    Then every answer matches the public contract

  Scenario: a release that does not intend to change the public contract serves an identical one or fails to build
    Given a release that does not intend to change the public contract
    When it is built
    Then it serves an identical public contract, or the build fails

  Scenario: a breaking change ships as a new version with the prior one served for at least six months
    Given a breaking change to the public contract
    When it ships
    Then it is a new version
    And the prior version is served for at least six months
