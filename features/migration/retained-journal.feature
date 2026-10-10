Feature: A player erased during retention is erased from the retained journal too
  Years after the cutover a former player asks to be forgotten. The brand erases them; the
  original's journal, kept for the regulator, still holds their name in the clear. The operator
  runs the original's erasure against the retained journal, and the certificate names both.

  Background:
    Given the retained journal of brand "spinvibe"

  Scenario: an erasure applied for a player from before the cutover removes them from the retained journal while the audit projection keeps them
    Given an erasure applied in the brand for a player who existed before the cutover, with no hold covering the retention period
    When the original's erasure is run against the retained journal
    Then the retained journal no longer holds the player's entries, snapshots, projection rows or lookup rows
    And the audit projection still does

  Scenario: an erasure whose hold lasts the whole retention period needs no per-player step, because the retained journal is deleted as a whole
    Given an erasure whose retention hold lasts the whole retention period
    When the retention lapses
    Then the retained journal is deleted as a whole
    And no per-player step is needed

  Scenario: a read of the retained journal needs a second person and a stated reason and leaves a record in the shared audit
    Given an operator of the installation
    When they request a read of the retained journal
    Then it needs a second person and a stated reason
    And it leaves a record in the shared audit
