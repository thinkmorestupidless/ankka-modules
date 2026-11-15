Feature: Investigating a player
  An operator finds a player from whatever they were given, reads the player's standing at a
  glance, and follows one timeline of everything that happened to the player, with the cause of
  each event and any gap named.

  Scenario Outline: any reference an operator pastes finds what it names, or says it names nothing in this brand
    Given a player of the brand with a deposit, a withdrawal request, a reward and a settled round
    When an operator searches for the player's "<reference>"
    Then the operator is shown "<found>"

    Examples:
      | reference                 | found                   |
      | player id                 | the player              |
      | email                     | the player              |
      | provider round id         | the round               |
      | provider transaction id   | the round               |
      | payment reference         | the deposit             |
      | withdrawal request id     | the withdrawal request  |
      | reward id                 | the reward              |
      | id of another brand       | nothing in this brand   |

  Scenario: a player's page shows the player's standing without scrolling
    Given a player with a limit, restricted funds, a pinned note and an open session
    When an operator opens the player
    Then the operator sees the player's identity, player status, identity status, limits, balances, restricted funds, lifetime figures, pinned note, flags and presence at once

  Scenario: a player's timeline holds every kind of event in order, each with its cause
    Given a player with a deposit, a round, a reward, an adjustment, a limit, a note and a withdrawal request
    When an operator reads the player's timeline
    Then every one of them is shown in the order it happened
    And each shows whether the player, an operator by name, the system or a provider caused it

  Scenario: a timeline whose source cannot be read names the source and the gap
    Given a player whose rounds cannot be read for the last day
    When an operator reads the player's timeline
    Then the timeline says rounds are missing for the last day
    And the timeline is not presented as complete

  Scenario: a round shows its bets and wins with the total staked, won and the net, and is marked when unsettled
    Given a round with two bets and one win, and a round with a bet and no settlement
    When an operator opens each round
    Then the first shows its bets, its win, the total staked, the total won and the net
    And the second is marked unsettled
