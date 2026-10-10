Feature: A person registers and becomes a player of the brand
  A visitor gives an email address, a password and a currency, and optionally names, a date of
  birth, a country, a language, a bonus code and the tag the link they arrived by carried. They
  become a player with an id, pending email verification; a verification email is sent; their
  network address and device fingerprint are recorded; consents are recorded as given; and the
  brand's other parts learn of the registration from one fact.

  Background:
    Given brand "goldvibe"

  Scenario: a registration with a new email address, an acceptable password and an offered currency creates a player pending email verification
    Given an email address not yet registered at the brand
    When a visitor registers with it, a password the realm accepts and a currency the brand offers
    Then they are a player with player status "pending_email_verification"
    And the answer carries the player id
    And a verification email is sent
    And the "player_registered" fact carries the registered time, country, currency, the tag, the click id and the bonus code as presented

  Scenario: a registration with an email address already registered is refused and nothing is created
    Given an email address already registered at the brand
    When a visitor registers with it
    Then the registration is refused as "email_already_exists"
    And nothing is created

  Scenario: a registration with a password below the realm's policy is refused and nothing is created
    Given a password below the realm's policy
    When a visitor registers with it
    Then the registration is refused as "password_too_weak"
    And nothing is created

  Scenario: a registration carrying a tag that is not well formed succeeds with no tag on the fact
    Given a registration carrying a tag that is not well formed
    When it is received
    Then the registration succeeds
    And the "player_registered" fact carries no tag

  Scenario Outline: a registration missing a required field is refused naming the three required fields
    Given a registration missing <field>
    When it is received
    Then it is refused naming the email address, the password and the currency as required

    Examples:
      | field             |
      | the email address |
      | the password      |
      | the currency      |

  Scenario: a registration with marketing consents given records them with their time and source
    Given a registration with marketing consents given
    When it succeeds
    Then the consents are recorded with the time they were given and the source "registration"

  Scenario: following the verification link within a day verifies the address and makes the player active, and following it again changes nothing
    Given a registered player pending email verification
    When they follow the verification link within "24" hours
    Then the realm marks the address verified
    And the player status is "active"
    And following the link again changes nothing

  Scenario: a registration keeps the network address and its country as personal data without overwriting the player's own country
    Given a registration from a network address
    When it succeeds
    Then the network address and the country it resolves to are kept on the player as personal data
    And the player's own country is not overwritten by it
