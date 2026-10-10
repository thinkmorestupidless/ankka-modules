Feature: A conversation with the support agent is durable, bounded, and forgotten with the player
  A conversation survives a restart and a page change; it is compacted when long, kept for the
  brand's retention, and encrypted under the player's key so an erasure makes it unreadable.

  Background:
    Given a signed-in player of brand "goldvibe" whose support agent is on

  Scenario: a conversation survives a restart and the model is given the earlier turns
    Given a conversation of three turns
    When the support agent restarts and the player sends a fourth turn
    Then the model is given the three earlier turns

  Scenario: a conversation past the compaction size has its oldest turns replaced by a summary and the most recent ten kept verbatim
    Given a conversation whose text passes the compaction size
    When the next turn ends
    Then the oldest turns are replaced by a summary
    And the most recent ten are kept verbatim

  Scenario: a conversation untouched for the brand's retention is forgotten and a new turn starts a new one
    Given a conversation untouched for the brand's retention
    When the retention lapses
    Then the conversation is forgotten
    And a new turn starts a new conversation

  Scenario: an erased player's conversations read as erased and a turn on any of them starts with no history
    Given the player is erased
    When erasure is applied
    Then every conversation of the player reads as erased
    And a turn on any of them starts with no history

  Scenario: a player continuing the chat on another page continues the same conversation under the same session id
    Given a conversation of the player
    When they open the chat on another page and continue
    Then the same conversation continues under the same session id
