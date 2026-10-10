Feature: The audit record survives everything
  Records are immutable, kept for the installation's window or the brand's longer one, backed up,
  unaffected by a player's erasure, and rebuilt only from backup, never from the brands' facts.

  Scenario: no role can change or delete a record in the shared store, because no such action exists
    Given a record in the shared audit's store
    When any role attempts to change or delete it
    Then there is no such action: no route, command or role can

  Scenario: an erased player's records are present and unchanged under the pseudonym, and the registry's row for the id is unchanged
    Given a player erased at a brand
    When their records are read from the shared audit
    Then every record is present and unchanged, naming the pseudonym
    And the registry's row for the player id is unchanged, since an id is not personal data

  Scenario: a brand's retention raised to ten years keeps its records ten years while another brand's keep the installation's seven
    Given a brand whose licence requires ten years
    When its retention is set to "10" years
    Then records of that brand are kept "10" years
    And another brand's records are kept the installation's "7" years

  Scenario: a brand's retention cannot be lowered below the installation's
    Given a brand whose retention is the installation's "7" years
    When its retention is set to "5" years
    Then it is refused

  Scenario: a restore of the shared audit's database re-reads the records after the restore point from each brand's facts where they still reach, and names any brand whose facts no longer do
    Given the shared audit's database restored to a point in time
    When the shared audit resumes
    Then the records after the restore point are re-read from each brand's audit records where the brand still holds them
    And the shared audit's status names any brand whose audit records no longer reach back to the restore point
