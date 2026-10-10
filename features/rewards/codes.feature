Feature: A code unlocks an offer: the brand's or an affiliate's
  A player enters a code. A brand's offer code makes its offer visible and selects it; an
  affiliate's bonus code does the same for the offer it names, and the fact it produces lets the
  brand's attribution bind the player.

  Background:
    Given brand "goldvibe"

  Scenario: a brand's offer code entered in any case makes the offer visible and selects it
    Given an active offer carrying offer code "WELCOME"
    When a player enters "welcome"
    Then the offer becomes visible to them and is selected, regardless of case

  Scenario: a code no live offer carries and no bonus code document holds is answered invalid
    Given no live offer carrying the code "NOPE" and no bonus code "NOPE" in the programme's bonus code document
    When a player enters "NOPE"
    Then they are told "invalid_code"

  Scenario: an affiliate's bonus code from the programme's document selects the offer it names and the reward records the affiliate source
    Given the programme's bonus code document for the brand holds "AFF100" naming an offer
    When a player enters "AFF100"
    Then the named offer is selected
    And the reward records the affiliate source
    And the reward's facts carry the code and the programme's tag

  Scenario: an operator creating a brand code already in the programme's document is refused
    Given the programme's bonus code document for the brand holds "AFF100"
    When an operator creates a brand offer code "aff100"
    Then it is refused as "code_already_in_use"

  Scenario: a code entered as a preview tells the player the offer's terms and selects nothing
    Given an active offer carrying offer code "WELCOME"
    When a player enters "WELCOME" as a preview
    Then they are told the offer's terms
    And nothing is selected
