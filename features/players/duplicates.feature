Feature: A duplicate account is detected and reviewed
  When a player registers, the brand compares them with every existing player by the normalised
  given name, family name and date of birth, and by device fingerprint. A match flags both players
  as suspected duplicates, links them, and opens a fraud case an operator reviews or dismisses; the
  brand's rewards withhold restricted funds from a flagged player.

  Background:
    Given brand "goldvibe"

  Scenario: a registration with the same name and date of birth as a player flags both as suspected duplicates and opens a fraud case with the identity signal
    Given a player "A" with a name and date of birth
    When "B" registers with the same name and date of birth
    Then both are flagged as suspected duplicates linked to each other
    And a fraud case opens naming the signal "identity"
    And a "player_fraud_flagged" fact is produced for each

  Scenario: a registration with the same device fingerprint as a player flags both and opens a fraud case with the device signal
    Given a player with a device fingerprint
    When another registers with the same device fingerprint
    Then both are flagged as suspected duplicates linked to each other
    And a fraud case opens naming the signal "device"
    And a "player_fraud_flagged" fact is produced for each

  Scenario: a flagged player's claim of a reward is withheld by the brand's rewards from the flag they read
    Given a flagged player
    When they claim a reward
    Then the brand's rewards withhold it, from the flag they read on the player's facts

  Scenario: dismissing a fraud case clears the flag of every player it links
    Given an open fraud case
    When an operator dismisses it
    Then every player it links has the flag cleared
    And a "player_fraud_cleared" fact is produced

  Scenario: reviewing a fraud case marks it reviewed with the operator and the time
    Given an open fraud case
    When an operator reviews it
    Then the case is marked reviewed with the operator and the time

  Scenario: a brand with detection turned off compares nothing on registration
    Given the brand's duplicate detection is turned off
    When a player registers
    Then nothing is compared
    And no fraud case opens

  Scenario: a registration without names, date of birth or device fingerprint compares nothing
    Given a registration without names or date of birth and without a device fingerprint
    When it succeeds
    Then nothing is compared
