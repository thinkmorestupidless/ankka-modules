Feature: Links, bonus codes and invite links carry the programme's tag
  An affiliate creates links, bonus codes and invite links; each carries a tag the programme signs,
  and a brand verifies without asking the programme. Clicks land at the brand and reach the
  programme as facts.

  Scenario: a new link carries a tag signed by the programme naming the affiliate, the link and the sub-ids
    Given an affiliate with an active membership at brand "A"
    When they create a link for brand "A" with a campaign and sub-ids
    Then the link exists with a tag signed by the programme naming the programme, the affiliate, the link and the sub-ids
    And the brand's landing address carries the tag

  Scenario: a click fact from a brand is recorded against its link and raises the link's click count
    Given a brand that recorded a click on the programme's link
    When its "click" fact arrives
    Then the programme records the click against the link with the click id, device type and country the fact carries
    And the link's click count rises by one

  Scenario: a brand holding the programme's key document verifies a tag without calling the programme
    Given a brand holding the programme's key document
    When a player registers with a tag the programme signed
    Then the brand verifies the tag without calling the programme

  Scenario: a tag for a link on the programme's revocation list binds no one and the programme receives no fact for the affiliate
    Given a brand holding the programme's revocation list, which names a link
    When a player registers with a tag for that link
    Then the brand binds no one
    And the programme receives no fact for the affiliate

  Scenario: a bonus code is unique at a brand regardless of case
    Given a bonus code "SPIN10" at brand "A"
    When an affiliate creates a bonus code "spin10" at brand "A"
    Then it is refused as a duplicate

  Scenario: two concurrent creations of the same bonus code text yield one code
    Given no bonus code "SPIN10" at brand "A"
    When two affiliates create a bonus code "SPIN10" at brand "A" at the same time
    Then one bonus code "SPIN10" exists at brand "A"

  Scenario: revoking a bonus code frees its text for later reuse
    Given a revoked bonus code "SPIN10" at brand "A"
    When an affiliate creates a bonus code "SPIN10" at brand "A"
    Then the bonus code is created

  Scenario: the legacy tag document maps each imported legacy tag to its affiliate and link during the transition period
    Given a legacy tag imported from brand "spinvibe"
    When the programme publishes its legacy tag document during the transition period
    Then the legacy tag document maps the legacy tag to the affiliate and the link it was imported against

  Scenario: the legacy tag document is withdrawn after the transition period
    Given the transition period has ended
    When the programme publishes its documents
    Then the legacy tag document is withdrawn

  Scenario: a link's click count comes from click facts and never from a counter on the account
    Given an affiliate's link
    When they read the link
    Then its click count comes from the "click" facts received and never from a counter on the account
