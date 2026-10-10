Feature: A player's open page learns the inbox changed within seconds
  A player has the lobby open on two devices. An operator sends them an inbox item; the brand's
  player records write it and produce a fact. Engagement pushes a content-free inbox changed signal
  down every push connection it holds for that player, on whichever instance holds it, and
  produces a delivered fact. The page fetches the inbox again. A connection whose sign-in stops
  verifying is closed with a reason.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a player opening the stream with their sign-in is answered ready, kept alive, and told before any close
    Given the player's sign-in carries their id
    When the player opens the stream
    Then a "ready" frame names them
    And a heartbeat arrives every "15" seconds
    And a closing frame with a reason precedes any close the brand makes

  Scenario: an inbox item created reaches each of the player's connections on two instances as one inbox changed push, and one delivered fact counts them
    Given push connections for the player on two instances
    When a "player_message_created" fact arrives
    Then each connection gets one push of kind "inbox.changed" with the item's correlation id, within "2" seconds
    And one "push_delivered" fact reports two connections

  Scenario: an inbox item for a player with no connection anywhere pushes nothing and produces no fact
    Given no push connection for the player anywhere
    When a "player_message_created" fact arrives
    Then nothing is pushed
    And no fact is produced

  Scenario: a connection whose sign-in no longer verifies at the re-check is closed as invalid
    Given a push connection whose sign-in no longer verifies at the "30" second re-check
    When the check runs
    Then the connection is closed with "token_invalid"

  Scenario: an operator's or a part's sign-in without a player id is refused the stream
    Given an operator's or a part's sign-in without a player id
    When it opens the stream
    Then it is refused as "subject_unresolved"

  Scenario: an inbox item read on one device pushes inbox changed to every other connection so the unread count converges
    Given push connections for the player on two devices
    When a "player_message_read" fact arrives from one device
    Then every other connection gets an "inbox.changed" push
