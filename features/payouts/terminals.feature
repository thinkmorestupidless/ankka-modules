Feature: The player is offered the terminals they may withdraw to, and saves destinations in their own name
  Before requesting, the player's account lists the terminals they may use, ranked: every active
  terminal of the brand that pays withdrawals; a "VISA" terminal only if they have deposited by
  "VISA"; terminals of a payment provider they have deposited with first, then by name. What is
  offered and what is accepted cannot differ.

  Background:
    Given a player with a wallet in "EUR"

  Scenario: a VISA terminal is absent for a player who never deposited by VISA
    Given a brand with an active "VISA" terminal and the player never deposited by "VISA"
    When the player's terminals are listed
    Then the "VISA" terminal is absent

  Scenario: a VISA terminal is present for a player who deposited by VISA
    Given a brand with an active "VISA" terminal and the player deposited by "VISA"
    When the player's terminals are listed
    Then the "VISA" terminal is present

  Scenario: a terminal of a payment provider the player deposited with is listed first, in the same order on every read
    Given two eligible terminals, one of a payment provider the player deposited with
    When the player's terminals are listed
    Then that one is first
    And the order is the same on every read

  Scenario Outline: a terminal that is inactive or does not pay withdrawals is absent and a request naming it is refused
    Given a terminal that <state>
    When the player's terminals are listed
    Then the terminal is absent
    And a withdrawal request naming it is refused as not eligible

    Examples:
      | state                     |
      | is inactive               |
      | does not pay withdrawals  |

  Scenario Outline: a destination is saved only in a name that matches the player's
    Given the player's profile names them "Anna Example"
    When the player saves a destination in the name "<name>"
    Then <outcome>

    Examples:
      | name       | outcome                                 |
      | A. Example | it is saved                             |
      | B. Other   | it is refused as blocked by the name check |

  Scenario: saving a destination with no name on the profile is refused as profile incomplete
    Given the player's profile has no name
    When the player saves a destination in the name "A. Example"
    Then it is refused as profile incomplete

  Scenario: setting a second destination as default makes the first no longer default
    Given two saved destinations, the first the default
    When the player sets the second as default
    Then the first is no longer default

  Scenario: deleting the default destination leaves it unoffered and no destination default
    Given two saved destinations, the first the default
    When the player deletes the first
    Then it is no longer offered
    And no destination is default
