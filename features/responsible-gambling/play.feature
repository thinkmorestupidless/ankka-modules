Feature: Play and sign-in are refused while a break or an exclusion stands
  A player on a break cannot sign in, launch a game or place a bet until the break ends; an excluded
  player never can. A player at their session limit for the day cannot launch.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a sign-in by a player on a break is refused with the date the break ends, by the brand and by the realm alike
    Given the player is on a break
    When they sign in
    Then the sign-in is refused with the date the break ends
    And the brand's player records and the realm refuse alike

  Scenario: a sign-in by a self-excluded player is refused as permanently excluded, directing them to support
    Given the player is self-excluded
    When they sign in
    Then the sign-in is refused as permanently excluded
    And they are directed to support

  Scenario: a launch for a player on a break is answered cooling off with the end of the break and refused
    Given the player is on a break
    When the game provider asks whether they may play
    Then the answer is cooling off with the end of the break
    And the launch is refused

  Scenario: a player whose sessions today total their session limit is answered session limit reached
    Given a session limit of "60" minutes and sessions totalling "60" minutes started today, open ones counted to now
    When the game provider asks whether they may play
    Then the answer is session limit reached, with "60" and "60"

  Scenario: a player under their session limit is answered may play
    Given a session limit of "60" minutes and sessions totalling "45" minutes started today
    When the game provider asks whether they may play
    Then the answer is may play

  Scenario: a session started yesterday does not count against today's session limit
    Given the player started a session yesterday and has played nothing today
    When the game provider asks whether they may play
    Then yesterday's minutes do not count

  Scenario Outline: a bet for a player on a break or self-excluded is refused as locked with the balance
    Given the player is <state>
    When the game provider debits a bet
    Then the wallet asks responsible gambling
    And the bet is refused as locked, with the balance

    Examples:
      | state         |
      | on a break    |
      | self-excluded |

  Scenario Outline: a caller asking responsible gambling while it cannot be reached is answered unavailable and refuses
    Given responsible gambling cannot be reached
    When <caller> asks it
    Then the answer is unavailable
    And the caller refuses

    Examples:
      | caller                     |
      | the brand's player records |
      | the game provider          |
      | the wallet                 |

  Scenario: a player whose break ended is refused nothing for the break
    Given the player's break has ended
    When they sign in, launch a game and place a bet
    Then nothing refuses them for the break
