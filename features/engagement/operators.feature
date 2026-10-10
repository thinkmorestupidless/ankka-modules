Feature: An operator sees what was sent, what failed, and who is online
  An operator opens a player and sees every send the brand's engagement made for them. A brand
  page lists the parked sends, sends the CRM refused after retries, and lets the operator retry one
  or discard it. The online filter reads presence. The configuration page shows the CRM settings
  with masked secrets, and a seed action fires one identify and one track per trigger at a seed
  person so the CRM campaign builder lists every track before any player has caused one.

  Background:
    Given brand "goldvibe"

  Scenario: an operator reads a player's sends newest first with event, class, channel, time, outcome and reason
    Given sends for a player
    When an operator with the brand's role reads them
    Then each shows its track, class, channel, time, outcome and reason, newest first, paged

  Scenario: a parked send retried and accepted by the CRM becomes accepted and is no longer parked
    Given a parked send after retries
    When an operator retries it and the CRM accepts
    Then the send record's outcome becomes "accepted"
    And it is no longer a parked send

  Scenario: a parked send discarded records who discarded it and why
    Given a parked send after retries
    When an operator discards it with a reason
    Then the record names who discarded it and why

  Scenario: a new CRM app key is stored in the secret store and read back masked, and a masked value sent back preserves the secret
    Given the brand's CRM configuration page
    When an operator saves a new app key
    Then it is stored in the secret store
    And it is read back masked, as its first three characters and asterisks
    And a masked value sent back unchanged preserves the stored secret

  Scenario: seeding the track list fires one identify and one track per trigger at the seed person, and seeding twice updates the same person
    Given the brand's CRM credential saved
    When an operator seeds the track list
    Then one identify and one track per trigger reach the seed person "_seed_goldvibe"
    And the answer counts them
    And seeding twice updates the same person

  Scenario: an operator reading presence sees each player with an open connection as online with the time they came online
    Given players with open push connections
    When an operator reads presence
    Then each is listed as online with the time they came online
