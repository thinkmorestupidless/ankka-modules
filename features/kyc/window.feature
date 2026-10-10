Feature: The verification window runs out
  On a brand whose KYC policy sets a verification window, a player who registers has that many days
  to become verified. When the window lapses unverified, the account is suspended if the policy says
  so, or flagged for review if not.

  Scenario: a registration on a brand with a three-day window sets the player's deadline three days out
    Given a brand with a "3" day verification window
    When a player registers
    Then the player's deadline is "3" days from registration

  Scenario: a registration on a brand with no window sets no deadline
    Given a brand with no verification window
    When a player registers
    Then the player has no deadline

  Scenario: a deadline passing on an unverified player under a policy that suspends on expiry suspends them once
    Given a deadline passed on a player still unverified and a KYC policy that suspends on expiry
    When the timer fires
    Then the brand's player records are told to suspend the player with reason "kyc_window_expired", once
    And a "player_kyc_review_required" fact is published with reason "window"

  Scenario: a deadline passing on an unverified player under a policy that does not suspend flags them for review
    Given a deadline passed on a player still unverified and a KYC policy that does not suspend on expiry
    When the timer fires
    Then the player is flagged for review with reason "window"
    And the player is not suspended

  Scenario: a deadline passing on a player verified before it does nothing
    Given a player verified before their deadline
    When the timer fires
    Then nothing happens

  Scenario: a changed window applies to players registered after it and not before
    Given a KYC policy whose verification window is changed
    When it is saved
    Then players registered before keep their deadline
    And players registered after get the new one
