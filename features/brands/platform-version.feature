Feature: Every part of every brand runs on one version of the platform
  The services of a brand, the services of the shared projects and the contract are built on one
  version of the platform. Moving them all to a new one changes nothing a player, an operator or a
  provider observes, and a brand changes nothing of its own to take it.

  Scenario: every service, every module and the contract name one version of the platform
    Given the repository at the platform version "0.18.0"
    When every service, every module manifest and the contract are read
    Then each names the platform version "0.18.0" and no other

  Scenario: a service moved to a new version of the platform replays every recording as it did before
    Given a service moved to the platform version "0.18.0"
    When its parity tests run
    Then every recording replays
    And no recording was changed

  Scenario: a brand takes a new version of the platform with its values unchanged
    Given the values of brand "guyanabet"
    When the brand's modules are rendered
    Then every service of the brand runs on the platform version "0.18.0"
    And the brand's values are unchanged

  Scenario: a brand is refused on an installation that cannot run the modules' version of the platform
    Given an installation that runs no platform version later than "0.16.3"
    When brand "guyanabet" is applied to it
    Then it is refused, naming the platform version the installation cannot run
    And nothing of the brand is deployed
