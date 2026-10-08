Feature: A visitor arrives on an affiliate's link and registers
  An affiliate on any network puts a link to the brand in front of a visitor. The visitor lands,
  the brand counts one click for that link, and when the visitor registers within the attribution
  window the brand binds the new player to that network, affiliate, link and sub-ids, once and for
  good, and emits a registration fact that names them.

  Background:
    Given a network the brand knows

  Scenario: a landing with a valid tag records one click per browser session and emits a click fact
    Given a link whose tag is valid and issued by the network
    When a visitor lands on the link
    Then the brand records one click for that link in this browser session
    And the click holds the device type and a digest of the visitor's address and never the address itself
    And a "click" fact is emitted naming the network, affiliate, link, campaign, sub-ids and click id

  Scenario: a visitor who registers within the attribution window is bound once and for good
    Given a visitor who landed with a valid tag
    When they register before the attribution window has passed
    Then the player is bound to that network, affiliate, link and sub-ids with the click id
    And a "registration" fact is emitted naming all of them and the player's pseudonym for that network

  Scenario: a tag older than the attribution window binds nothing and the registration is organic
    Given a visitor whose tag is older than the brand's attribution window
    When they register
    Then the player is recorded as organic
    And the stale tag is kept on the attribution record for reporting
    And no "registration" fact is emitted for the affiliate

  Scenario: a later tag does not change a binding and is recorded as a later touch
    Given a player already bound to an affiliate
    When they arrive with a different tag, sign in and play
    Then the binding does not change
    And the later touch is recorded on the player's attribution record

  Scenario Outline: a tag the brand cannot verify never fails a registration and is kept with its reason
    Given a visitor who landed with a tag the brand cannot verify because of <reason>
    When they register
    Then the registration succeeds
    And the player is organic
    And the refused tag is kept with the reason

    Examples:
      | reason                         |
      | a bad signature                |
      | an unknown network             |
      | a key the network has withdrawn |

  Scenario: a tag for a revoked link binds nothing and the record says the link was revoked
    Given a visitor who landed with a tag for a link the network has revoked
    When they register
    Then the player is organic
    And the attribution record says the link was revoked
