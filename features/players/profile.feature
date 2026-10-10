Feature: A player keeps their profile, email addresses and consents
  A player reads and edits their profile. Some fields lock once set; the country must be one the
  brand allows. A player keeps several email addresses, verifies each, makes one their sign-in
  address and removes others; changes their password with an emailed confirmation; gives or
  withdraws marketing consents; and records their cookie decision per device.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a player reads their profile with every personal field readable to them and to no other player
    Given the player has a profile
    When they read it
    Then they see every field of the profile
    And every personal field is readable to them and to no other player

  Scenario: a locked date of birth is unchanged by an edit, the other fields apply, and the fact names only what changed
    Given the player's date of birth is set
    When they send a different date of birth with other fields
    Then the date of birth is unchanged
    And the other fields sent are applied
    And the "player_profile_updated" fact names only the fields the player changed and carries the completeness fields KYC reads

  Scenario: a country the brand does not allow is refused and nothing changes
    Given a country the brand does not allow
    When the player sets it
    Then the edit is refused as "invalid_country"
    And nothing changes

  Scenario: a second email address added and verified is verified and not the sign-in address
    Given the player has one email address
    When they add a second and follow its verification link
    Then the second is verified
    And it is not the sign-in address

  Scenario: making a verified second email address the sign-in address makes the realm sign them in with it
    Given the player has a verified second email address
    When they make it their sign-in address
    Then it is the address the realm signs them in with
    And the first is no longer the sign-in address

  Scenario: a password change with the current password sends a confirmation email and changes the password only when its link is followed
    Given the player changing their password
    When they give the current password and the new one
    Then a confirmation email is sent
    And the password changes only when the email's link is followed

  Scenario: a password change with a wrong current password is refused
    Given the player changing their password
    When they give a wrong current password
    Then it is refused as "invalid_password"

  Scenario: a withdrawn email marketing consent reads as withdrawn with its time, and marketing email stops from that fact
    Given the player gave email marketing consent
    When they withdraw it
    Then the consent reads as withdrawn with the time
    And a "player_consent_changed" fact is produced
    And the brand's engagement stops sending marketing email from that fact

  Scenario: a cookie decision recorded with every field is kept per device and visible to an operator
    Given the player's device
    When the site records a cookie decision for it with analytics, marketing, version and time
    Then it is kept per device
    And an operator can see it

  Scenario: a cookie decision with a field missing is refused rather than defaulted
    Given the player's device
    When the site records a cookie decision for it with a field missing
    Then it is refused rather than defaulted

  Scenario: an operator's correction of a date of birth that would make the player younger than eighteen is refused
    Given an operator correcting the player's date of birth with a reason
    When the corrected date makes the player younger than "18"
    Then it is refused

  Scenario: an operator's correction of a date of birth to eighteen or over is applied and audited with the reason
    Given an operator correcting the player's date of birth with a reason
    When the corrected date makes the player "18" or over
    Then it is applied
    And it is audited with the reason
