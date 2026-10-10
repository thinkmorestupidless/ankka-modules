Feature: A pseudonym is re-identified on demand, and that is recorded
  A regulator names a pseudonym from an export and asks who it is. A compliance officer with the
  re-identification role asks the registry, states the reason, and is told the brand, the kind and
  the real id. The question is itself a record visible to the brand.

  Scenario: a compliance officer with the re-identification role is told who a pseudonym is, and the re-identification is recorded
    Given a compliance officer with the re-identification role for brand "goldvibe"
    When they ask who a pseudonym of brand "goldvibe" is, giving a reason code
    Then they are told the brand, the kind, player, operator, affiliate or service, and the real id
    And a record of the re-identification names them, the pseudonym and the reason

  Scenario: a compliance officer without the re-identification role is refused, and the refusal is recorded
    Given a compliance officer without the re-identification role
    When they ask who a pseudonym is
    Then they are refused
    And the refusal is recorded

  Scenario: a real id asked for its pseudonym at a brand by the re-identification role is answered or issued, and the lookup is recorded
    Given a compliance officer with the re-identification role for brand "goldvibe"
    When they ask for the pseudonym of a real id at brand "goldvibe"
    Then the pseudonym is returned, or issued if none exists
    And the lookup is recorded

  Scenario: a brand operator reading the activity tab sees real ids with no re-identification needed inside the brand
    Given a brand operator of brand "goldvibe"
    When they read a player's activity tab
    Then the real ids are already there, with no re-identification needed inside the brand
