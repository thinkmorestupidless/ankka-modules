Feature: Compliance asks what happened, per brand and across brands
  A brand's compliance officer asks for every action on one player in a period; the installation's
  compliance officer asks for every operator adjustment above an amount across every brand; a
  regulator asks for the brand's record for a month as a file. Each answer comes from the shared
  audit, in pseudonyms, and is itself recorded.

  Scenario: a query by a brand role for a pseudonym and a period lists every matching record of that brand in order of occurrence and none of another brand
    Given a compliance officer with the role for brand "goldvibe"
    When they query the shared audit by a player's pseudonym and a period
    Then every matching record of brand "goldvibe" is listed in order of occurrence, paged
    And no record of another brand is listed

  Scenario: a query by the installation's role across brands lists records from every brand, each naming its brand
    Given a compliance officer with the installation's role
    When they query the shared audit across brands for action "adjust" above "100000" minor units in a period
    Then records from every brand are listed
    And each names its brand

  Scenario: a query by a brand role naming another brand is refused
    Given a compliance officer with the role for brand "goldvibe"
    When they query the shared audit naming brand "spinvibe"
    Then it is refused

  Scenario: an export of a brand's records for a period is a file in the regulator's format, in pseudonyms, and is itself a record
    Given a compliance officer with the role for brand "goldvibe"
    When they export the brand's records for a period
    Then a file in the regulator's agreed format is produced with every record in that period, in pseudonyms
    And the export is a record naming who exported what range

  Scenario: a query or an export is itself recorded with its actor, filters, range and count, and that record is not queried about recursively
    Given any query or export of the shared audit
    When it is run
    Then the shared audit writes a record of it naming the actor, the filters, the range and the count
    And that record is not itself queried about recursively

  Scenario: a query for a period older than the brand's retention answers what is retained and says from when
    Given a query for a period older than the brand's retention
    When it is run
    Then it answers what is retained
    And it says from when
