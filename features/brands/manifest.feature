Feature: A brand is created from one manifest and runs on its own
  An operator creates a brand by applying one manifest. The brand then serves its own players, and
  nothing another brand does reaches it. A brand takes real money only on an installation fit for it.

  Scenario: a brand is created from its manifest with every part at the version the manifest pins
    Given an installation whose shared payments, programme and audit are running
    When the operator applies the manifest for a brand named "goldvibe"
    Then brand "goldvibe" is running with every part of it at the version its manifest pins
    And a player can register and sign in at brand "goldvibe"
    And the shared payments, programme and audit are admitted to brand "goldvibe" as its manifest allows

  Scenario: moving one brand to a new version leaves another brand untouched
    Given brands "goldvibe" and "spinvibe" created from the same manifest
    When the wallet of brand "goldvibe" is moved to a new version
    Then the wallet of brand "spinvibe" keeps its version and serves without interruption

  Scenario: a manifest naming a part or a fact the contract does not list is refused before anything is created
    Given an installation whose shared payments, programme and audit are running
    When the operator applies a manifest that names a part the contract does not list
    Then the manifest is refused before anything is created, naming the unknown entry

  Scenario: the same key in two brands names two different things
    Given brands "goldvibe" and "spinvibe" each with a player known as "p1"
    When brand "goldvibe" reads the wallet of player "p1" in "EUR"
    Then it reads the wallet of its own player and not the wallet of the other brand's player

  Scenario Outline: a brand marked for real money is refused on an installation that is not fit for it
    Given an installation <condition>
    When a manifest marked for real money is applied
    Then it is refused, naming <named>

    Examples:
      | condition                                     | named                   |
      | that keeps no backup copy outside itself      | the backups             |
      | that keeps its facts in one copy only         | the copies of its facts |

  Scenario: a brand marked for real money is created on an installation meeting every condition
    Given an installation meeting every condition for real money
    When a manifest marked for real money is applied
    Then the brand is created and its status lists the conditions that were checked

  Scenario: a brand not marked for real money is created on a local installation
    Given a local installation
    When a manifest not marked for real money is applied
    Then the brand is created using only what the local installation has
