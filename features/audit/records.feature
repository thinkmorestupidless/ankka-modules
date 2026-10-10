Feature: Every action in a brand leaves one audit record
  An operator adjusts a player's balance with a reason; the wallet refuses a bet; a player sets a
  deposit limit; the support agent's tool suspends an account; the cashier hands a captured deposit
  to the wallet. Each is one audit record, written by the part that owns the action, in one shape,
  as the action is done, never later and never by another part.

  Background:
    Given a player of brand "goldvibe"

  Scenario: an operator's adjustment with a reason code is one record with the operator as actor, the player as subject, the wallet as the part and the balance's before and after
    Given an operator with a role for the brand
    When they adjust the player's real balance by "-500" with the reason code "duplicate_deposit"
    Then the brand's audit records hold one record whose actor is the operator, subject the player, part the wallet, action "adjust" and outcome done
    And its changes list the real balance's before and after

  Scenario: a bet refused for insufficient funds is one record with outcome refused, the reason code, the bet's key as causation and no change
    Given the player has a real balance of "1000"
    When the game provider places a bet of "1500"
    Then one record has outcome refused with reason code "insufficient_funds"
    And its causation is the bet's key
    And it lists no change

  Scenario: a player setting a deposit limit is one record with the player as actor and subject and the limit's before and after
    Given the player is signed in
    When they set a daily deposit limit
    Then one record has the player as actor and subject, the part responsible gambling, and the limit's before and after

  Scenario: a suspension by the support agent's tool is one record with actor kind agent, the approver, and the suspension's before and after
    Given the support agent
    When its tool call suspends the player
    Then one record has actor kind "agent" with the agent's instance id
    And it names the operator who approved the call as the approver
    And it lists the suspension's before and after

  Scenario: a captured deposit handed to the wallet by the cashier is one record with actor kind service, the payment id as correlation, and the balance's before and after
    Given a captured deposit handed to the wallet by the cashier
    When the wallet credits it
    Then one record has actor kind "service" naming the cashier
    And its correlation is the payment id
    And it lists the balance's before and after

  Scenario: an action repeated under the same key writes no second record
    Given an action a part of the brand performed under a key
    When the same action is repeated under the same key
    Then no second record is written, because the record id derives from the part, the entity and the key

  Scenario: a change to the email address names the field and carries no before or after value
    Given a record for a profile change
    When the changed field is the email address
    Then the change names the field
    And it carries no before or after value
