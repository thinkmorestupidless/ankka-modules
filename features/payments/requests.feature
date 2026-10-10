Feature: An operator sees what a sub-brand sent
  An operator of the shared payments reads a sub-brand's recent requests: what came in, when, under
  which request id, and how each was answered. They find the one whose answer was lost and see the
  session it made.

  Scenario: a sub-brand's recent requests are listed with their time, request id, amounts and outcome and nothing that names a player
    Given a sub-brand that sent four creates, two served, one refused for its key and one refused for its routing
    When an operator reads the sub-brand's recent requests
    Then all four are listed with their time, request id, amount, currency, country and outcome
    And none shows a player's name, email address, telephone number, postal address or network address

  Scenario: a request the cashier accepted whose answer could not be returned is read among the lost answers with what the cashier made
    Given a request the cashier accepted whose answer could not be returned to the sub-brand
    When an operator reads the lost answers
    Then the request is listed with the cashier session or the payment the cashier made
    And the operator can mark it seen

  Scenario: an operator whose roles do not name the shared payments is refused a sub-brand's recent requests
    Given an operator whose roles do not name the shared payments
    When they read a sub-brand's recent requests
    Then they are refused
