Feature: A fact crosses between the parts of a brand on the contract
  The part of a brand that owns a fact publishes it, in the kind and version the contract names,
  so that the facts about one player stay in order. Any part that needs the fact reads it and
  builds its own view; it never reads another part's store.

  Scenario: a bet placed is published as a fact about the wallet it belongs to
    Given a player of brand "goldvibe" with a wallet in "EUR"
    When the player places a bet of "1000" in round "r1"
    Then a "bet_placed" fact at version "1" is published for the brand's readers
    And the fact is about that wallet and names the player

  Scenario: a view rebuilt from a brand's facts a year later sees every fact
    Given facts a brand published a year ago
    When a reader rebuilds its view from the brand's facts
    Then it sees every fact

  Scenario: adding a field to a kind of fact keeps its version
    Given a kind of fact at version "1" read by two brands
    When a field is added to it
    Then the kind stays at version "1" and both brands keep reading it

  Scenario: changing what a kind of fact means is a new version beside the old
    Given a kind of fact at version "1" whose meaning changes
    When the change is made
    Then it is a new version beside the old, and the old is produced until no brand reads it

  Scenario: a deposit captured by the shared payments credits the brand's wallet once however many times it is delivered
    Given a player of brand "goldvibe" whose deposit of "5000" was captured by the shared payments
    When the capture is delivered to the brand three times
    Then the player's wallet is credited once
    And one "deposit_confirmed" fact is published for the brand's readers

  Scenario: the shared audit keeps a brand's audit records naming the player by a pseudonym
    Given brand "goldvibe" lets the shared audit read its audit records
    When a part of the brand records an audit record about a player
    Then the shared audit reads it and keeps it naming the player by a pseudonym and never by a real id
