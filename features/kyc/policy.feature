Feature: The brand's KYC policy comes from its licence
  An operator applies a licence profile to the brand and the KYC policy's fields take that profile's
  defaults: the deposit gate, the play gate, the deposit threshold, the source of funds threshold,
  the verification window, the required categories, and whether the window's expiry suspends. The
  operator may then change any field, which marks the profile custom.

  Scenario: a brand with no KYC policy reads the Curaçao default
    Given a brand with no KYC policy
    When its KYC policy is read
    Then it is the "curacao" default: no deposit gate, no play gate, no KYC currency, no deposit threshold, a "30" day verification window, no source of funds threshold, identity required, and suspend on expiry

  Scenario: applying the UKGC profile sets its gates, window, threshold and categories
    Given a brand
    When an operator applies the licence profile "ukgc"
    Then the KYC policy is: deposit gate verified, play gate verified, the KYC currency "GBP", no deposit threshold, a "3" day verification window, a source of funds threshold of "200000" minor units, identity and proof of address required, and suspend on expiry

  Scenario: applying the MGA profile sets its thresholds and categories with no window and no suspension
    Given a brand
    When an operator applies the licence profile "mga"
    Then the KYC policy is: no deposit gate, no play gate, the KYC currency "EUR", a deposit threshold of "200000", no verification window, a source of funds threshold of "1000000", identity and proof of address required, and no suspension on expiry

  Scenario: changing one field of a profile's policy changes only that field and marks the profile custom
    Given a brand on the licence profile "mga"
    When an operator sets the verification window to "60" days
    Then only that field changes
    And the licence profile reads "custom"

  Scenario: applying an unknown licence profile is refused naming the known profiles
    Given a brand
    When an operator applies the licence profile "atlantis"
    Then it is refused naming the known licence profiles

  Scenario: a saved KYC policy change is audited with the fields and values and governs the next gate decision
    Given a KYC policy change
    When it is saved
    Then an audit record names the operator, the fields and the old and new values
    And the next gate decision for any player uses it

  Scenario: an unauthenticated client asking the brand's requirements is told the required categories and nothing else
    Given a brand's KYC policy
    When an unauthenticated client asks the brand's requirements
    Then it is told the required categories and nothing else
