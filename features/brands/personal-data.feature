Feature: A player's personal data is marked and can be erased
  Every field that identifies a player is held as personal data under the player, in every part of
  the brand and in every fact the brand publishes. When a player is erased, every such field
  becomes unreadable, while balances, bets, payments and commission facts stay readable.

  Scenario: every field that identifies a player is kept as personal data under the player
    Given a registered player
    When the brand keeps their name, email address, telephone number, postal address, date of birth, identity documents or network address
    Then it keeps each as personal data under the player

  Scenario: a personal field in a fact is personal data under the player in the fact too
    Given a fact about a player
    When it carries a personal field
    Then the field is personal data under the player in the fact too

  Scenario: an erased player's personal fields read as erased and every amount, id and time reads as before
    Given an erased player
    When the brand, the shared payments, the shared audit or the programme reads a fact about that player
    Then every personal field reads as erased
    And every amount, id and time reads as before

  Scenario: the shared payments and the shared audit hold a player's id and nothing else that identifies them
    Given the shared payments or the shared audit
    When it holds anything about a player
    Then it holds the player's id and nothing else that identifies the player

  Scenario: an erasure requested under a retention hold waits for the hold to lapse and is then applied unasked
    Given a player under a retention hold
    When erasure is requested
    Then the request is held until the retention hold lapses, and is applied then without being asked again
