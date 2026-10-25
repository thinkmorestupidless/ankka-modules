Feature: A network's tags are trusted, rotated and retired, and a network can revoke a link and nothing else
  A brand trusts a network's tags because it holds the network's published signing keys. The
  network rotates a key without a visitor noticing; a brand that stops working with a network stops
  trusting its tags. A network can revoke a link or an affiliate, which stops new registrations
  binding to it; nothing a network says can unbind a player, move a fact or change an amount.

  Scenario: a tag naming a signing key the brand does not hold makes the brand fetch and cache the network's key document
    Given a network the brand's operator has added with the address of its key document
    When the brand first sees a tag naming a signing key it does not hold
    Then the brand fetches the key document
    And the brand caches the signing keys for the time the key document states
    And the brand verifies the tag

  Scenario: a tag signed with a key the network has withdrawn is refused once the brand's cache has refreshed
    Given a tag signed with a signing key the network has withdrawn from its key document
    When a visitor lands with it after the brand's cache has refreshed
    Then the tag is refused
    And the reason is kept

  Scenario: a legacy tag the programme's legacy tag document lists is treated as the programme's tag during the transition period
    Given a legacy tag the programme's legacy tag document lists
    When a visitor lands with it during the transition period
    Then it is treated as a tag from the programme for that link's affiliate, with no sub-ids

  Scenario Outline: the transition period ends at the first of 12 months or 90 days without a legacy tag
    Given a brand on the protocol for <time> and <legacy use>
    When a visitor lands with a legacy tag
    Then the tag is refused as legacy
    And the brand keeps the day the period ended and that <ended by> ended it

    Examples:
      | time             | legacy use                                   | ended by                     |
      | 12 months    | a legacy tag presented every month           | 12 months                |
      | four months      | no legacy tag presented for 90 days      | 90 days without a legacy tag |

  Scenario: a legacy tag after the transition period is refused and the player is organic
    Given the transition period has ended
    When a visitor lands with a legacy tag
    Then the tag is refused as legacy
    And the player is organic

  Scenario: a tag from a network the operator removed is refused and facts already emitted are unchanged
    Given a network the brand's operator has removed from the brand
    When a visitor lands with its tag
    Then the tag is refused as unknown
    And facts already emitted for its affiliates are unchanged

  Scenario: a revoked link binds no new player and players already bound stay bound
    Given a network's revocation list names a link
    When the brand refreshes the revocation list
    Then a tag for that link binds no new player from then on
    And players already bound to it stay bound

  Scenario: a revoked affiliate has every tag naming them refused for new bindings
    Given a network's revocation list names a whole affiliate
    When the brand refreshes the revocation list
    Then every tag naming that affiliate is refused for new bindings

  Scenario: anything but signing keys and revocations from a network is refused
    Given anything else a network sends to the brand
    When it arrives
    Then it is refused, because the reverse channel carries signing keys and revocations and nothing more
