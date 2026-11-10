Feature: An installation's values live with the installation
  What a module is lives with the modules. What an installation is, what each brand on it is, and
  which version of each module a brand runs there live in the installation's directory, outside
  the modules' own repository: the installation's settings, and for each brand its values and its
  version lock. A local installation and one example brand stay beside the modules. Whether an
  installation is fit for real money is read from what the installation reports about itself.

  Background:
    Given an installation's directory holding the installation's settings, and the values and version lock of brand "goldvibe"

  Scenario: a brand is applied from the values and the version lock kept in its installation's directory
    When the modules tool applies brand "goldvibe" naming the installation's directory
    Then brand "goldvibe" runs with the values the installation's directory holds for it
    And nothing about brand "goldvibe" is read from beside the modules

  Scenario: a brand kept in an installation's directory is unknown to the modules tool when the installation's directory is not named
    When the modules tool applies brand "goldvibe" naming no installation's directory
    Then it is refused, naming where it looked for the brand's values

  Scenario: one brand has different values on two installations and each installation applies its own
    Given a second installation's directory holding other values for brand "goldvibe"
    When the modules tool applies brand "goldvibe" naming the second installation's directory
    Then brand "goldvibe" runs there with the second installation's values
    And brand "goldvibe" on the first installation is unchanged

  Scenario: a local installation and the example brand are still applied from beside the modules with no installation's directory named
    Given a local installation
    When the modules tool applies the example brand naming no installation's directory
    Then the example brand is created using only what the local installation has

  Scenario: applying a brand brings it to the modules and versions its version lock names
    Given the version lock of brand "goldvibe" names the module "core" at version "0.3.0" and the module "casino" at version "0.3.0"
    When the modules tool applies brand "goldvibe" naming the installation's directory
    Then brand "goldvibe" runs the module "core" at version "0.3.0" and the module "casino" at version "0.3.0"
    And the module "core" is applied before the module "casino"
    And each module applied is a fact on the brand's platform topic naming its version

  Scenario: changing one brand's version lock moves that brand and leaves another brand untouched
    Given brand "goldvibe" and brand "spinvibe" both running the module "casino" at version "0.3.0"
    And the version lock of brand "goldvibe" changed to name the module "casino" at version "0.4.0"
    When the modules tool applies brand "goldvibe" naming the installation's directory
    Then brand "goldvibe" runs the module "casino" at version "0.4.0"
    And brand "spinvibe" runs the module "casino" at version "0.3.0"

  Scenario: a brand running a version its version lock does not name is reported as differing, naming the module and both versions
    Given brand "goldvibe" running the module "casino" at version "0.3.0"
    And the version lock of brand "goldvibe" names the module "casino" at version "0.4.0"
    When the modules tool reads the status of brand "goldvibe" naming the installation's directory
    Then the module "casino" is reported as differing, naming version "0.3.0" and version "0.4.0"
    And the status is refused

  Scenario: a version lock whose versions break a module's dependency range is refused before anything is applied
    Given the version lock of brand "goldvibe" names the module "casino" at a version outside its dependency range of the module "core"
    When the modules tool applies brand "goldvibe" naming the installation's directory
    Then the version lock is refused, naming the module "casino" and the range
    And nothing is applied

  Scenario: a module running in a brand and absent from its version lock is reported and is not removed by applying
    Given brand "goldvibe" running the module "casino"
    And the version lock of brand "goldvibe" names the module "core" only
    When the modules tool applies brand "goldvibe" naming the installation's directory
    Then the module "casino" is reported as absent from the version lock
    And the services of the module "casino" still run at brand "goldvibe"

  Scenario: a version lock on an installation the vendor serves is refused naming the vendor
    Given the installation's settings name the vendor
    When the modules tool validates the values of brand "goldvibe" naming the installation's directory
    Then the version lock is refused, naming the vendor as where versions are decided

  Scenario: a shared project is applied at the version its own version lock names
    Given the installation's directory holding a version lock for the shared payments at version "0.3.0"
    When the modules tool applies the shared payments naming the installation's directory
    Then the shared payments runs at version "0.3.0"
    And its sign-in address is the one the installation's settings name

  Scenario: whether an installation is fit for real money is read from what the installation reports and never from its settings
    Given an installation that reports every condition for real money
    And the values of brand "goldvibe" mark it for real money
    When the modules tool validates the values of brand "goldvibe" naming the installation's directory
    Then the brand is fit, and each condition names the installation as where it was read

  Scenario: an installation's settings restating a fact the installation reports are refused naming the entry
    Given the installation's settings state the number of copies of its facts the installation keeps
    When the modules tool validates the values of brand "goldvibe" naming the installation's directory
    Then the installation's settings are refused, naming the entry and where that fact is read
