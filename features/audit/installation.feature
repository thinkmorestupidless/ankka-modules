Feature: Installation actions on a brand are in the brand's record
  The installation deploys a new version to a brand, grants the brand's facts to the programme,
  restores the brand's database to a point in time, or applies an erasure. Each appears in the
  shared audit as an installation record of that brand, visible to the brand's compliance role, so
  the brand's story is one story.

  Background:
    Given brand "goldvibe"

  Scenario Outline: a deployment, rollback or switch to a restored database is an installation record naming the actor, the part, the version or restore point, and when
    Given a part of the brand
    When it is <action>
    Then a record with class "installation" names the actor, the part, the version or restore point, and when

    Examples:
      | action                            |
      | deployed                          |
      | rolled back                       |
      | switched to a restored database   |

  Scenario Outline: what a brand allows a shared part, made, accepted, declined, withdrawn or revoked, is a record naming the principal, the target and the change
    Given what the brand allows a shared part
    When it is <change>
    Then a record names the principal, the target and the change

    Examples:
      | change    |
      | made      |
      | accepted  |
      | declined  |
      | withdrawn |
      | revoked   |

  Scenario Outline: an erasure request filed, held, applied or completed is a record naming the subject's pseudonym, the state and when, and the record survives the erasure
    Given an erasure request for a player of the brand
    When it is <state>
    Then a record names the subject's pseudonym, the state "<state>" and when
    And the record survives the erasure

    Examples:
      | state     |
      | filed     |
      | held      |
      | applied   |
      | completed |

  Scenario: a brand compliance officer sees installation records beside brand records in one order of occurrence
    Given a compliance officer with the role for the brand
    When they query their brand's records
    Then records of class "installation" are listed beside records of class "brand" in one order of occurrence
