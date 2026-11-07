Feature: A game service joins the house provider through one contract
  A game service registers its games with the house provider and from then on is launched, checks
  launch tickets, opens, settles and cancels rounds only by asking the house provider, which signs,
  sends and retries every provider call. The game service never holds a signing key, never knows a
  brand's address and never sends a provider call itself.

  Background:
    Given a house provider serving a brand

  Scenario: a game registers its games and they appear in the games list a brand fetches with the game's own developer and category
    Given a game service with a game coded "loteca" in the category "pools"
    When the game service registers its games with the house provider
    Then the brand's request for the list of games answers "loteca" with the developer "eitheror" and the category "pools"

  Scenario: a launch for a registered code answers that game's page address carrying the launch ticket; an unregistered code is unknown game
    Given a game service that registered the code "loteca"
    When the brand asks the house provider to launch "loteca"
    Then the answer is the game service's game page address carrying the launch ticket
    And a launch of a code no game service registered is answered "unknown_game"

  Scenario: a game's check of a launch ticket sends one signed check to the brand and answers the session; a ticket the brand refuses is answered as refused and the game shows the session expired page
    Given a launch ticket the brand issued for "loteca"
    When the game service asks the house provider to check it
    Then the house provider sends one signed check to the brand
    And the game service is answered the brand, the player id, the currency, the country, the language and whether the session is in fun mode
    And a launch ticket the brand answers "session_expired" is answered to the game service as refused, and the game page shows the session expired page

  Scenario: a debit asked by a game is sent once, signed, under a new transaction id, and the brand's status and balance are answered to the game
    Given an open session of "loteca"
    When the game service asks for a debit of "150" minor units on a round it names
    Then the house provider sends one signed debit of "1.50" under a new transaction id
    And the brand's answer word and balance are answered to the game service

  Scenario: a debit the brand does not answer within the wait is answered to the game as pending and retried under the same transaction id until the brand answers or the schedule is exhausted
    Given an open session of "loteca" at a brand that does not answer
    When the game service asks for a debit
    Then the game service is answered a pending debit
    And the house provider retries the debit under the same transaction id on the retry schedule
    And the game service reads the brand's answer once the brand answers

  Scenario: a credit asked by a game is accepted at once and sent, retried on error or no answer, and the game can read whether it was acknowledged
    Given a round of "loteca" opened by a debit the brand answered "ok"
    When the game service asks for a credit of "300" minor units on the round
    Then the house provider accepts it at once and sends one signed credit of "3.00"
    And a credit the brand answers "error" or does not answer is retried on the retry schedule
    And the game service reads whether the brand acknowledged it

  Scenario: a credit of zero asked by a game is sent as the brand expects a loss
    Given a round of "loteca" opened by a debit the brand answered "ok"
    When the game service asks for a credit of "0" on the round
    Then the house provider sends a credit of "0.00" on the round

  Scenario: a cancel asked by a game references the debit's transaction id and the round is closed as cancelled on the brand's acknowledgement
    Given a round of "loteca" opened by a debit the brand answered "ok"
    When the game service asks to cancel the debit
    Then the house provider sends a cancel under a new cancel id naming the debit's transaction id
    And the round is closed as cancelled when the brand acknowledges the cancel

  Scenario: a round asked on a demo session sends no money callback and the game is told so
    Given an open session of "loteca" in fun mode
    When the game service asks for a debit on a round
    Then the house provider sends nothing
    And the game service is answered a demo round

  Scenario: a game asking for a round on a session of another game is refused
    Given an open session of "loteca" and a game service that registered only "scratch"
    When the second game service asks for a debit on that session
    Then the house provider refuses it
    And nothing is sent
