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

  Scenario: a registration is marked as under a duplicate check until the detector has run
    When a player registers at a brand with duplicate detection on
    Then the player's duplicate check is in progress until the brand has compared them with its players
    And the start and the end of the check are each a fact

  Scenario: a registration whose email is a variant of a player's from the same address flags both as suspected duplicates
    Given a player registered with the email "ada.lovelace@gmail.com" from an address
    When another registers with "adalovelace7@gmail.com" from the same address within a day
    Then both are flagged as suspected duplicates linked to each other
    And a fraud case opens

  Scenario: a registration whose email is a variant of a player's, with nothing else, blocks only its no-deposit offers
    Given a player registered with the email "ada.lovelace@gmail.com"
    When another registers with "ada.lovelace+promo@gmail.com" and nothing else in common
    Then the new player's no-deposit offers are blocked
    And neither player is flagged as a suspected duplicate

  Scenario: a registration whose comparison fails blocks only its no-deposit offers
    Given the comparison cannot be made when a player registers
    When the duplicate check ends
    Then the new player's no-deposit offers are blocked

  Scenario: registrations from one address at the rate the detector counts, with nothing else, change nothing
    Given three players registered from one address within a day, with nothing else in common
    When the duplicate check ends for the third
    Then no player is flagged and no offer is blocked

  Scenario: an operator clearing a player's no-deposit block lifts it and records who and when
    Given a player whose no-deposit offers are blocked
    When an operator with a fraud or compliance role clears the block
    Then the block is lifted with the operator and the time recorded
    And a fact says so
