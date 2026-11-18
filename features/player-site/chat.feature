Feature: A player chats with the support agent through the chat behaviour
  The chat behaviour is a player's conversation with the support agent as steps: ready, a turn being
  answered, an approval request awaiting the player's decision, and the conversation's end. The
  reply reaches the page part by part through the site server. The support agent's words are the
  brand's to the player; the behaviour adds none.

  Background:
    Given a player site of brand "guyanabet" whose brand runs the module "support"
    And a player signed in at the player site

  Scenario: a turn the player sends is answered part by part and then the chat is ready again
    Given the chat behaviour stands at ready
    When the player sends a turn
    Then the chat behaviour stands at answering
    And the page is given each part of the reply as it arrives
    And the chat behaviour stands at ready once the turn is answered

  Scenario: a conversation's first turn is sent under a new session id and its later turns under the same one
    Given the chat behaviour stands at ready with no conversation
    When the player sends a turn and then another
    Then both turns are sent under one session id the chat behaviour made

  Scenario: a page loaded again continues the same conversation
    Given the player has a conversation at the player site
    When the page is loaded again and the player sends a turn
    Then the turn is sent under the conversation's session id

  Scenario: a turn that ends with an approval request stands at awaiting the player's decision
    Given the chat behaviour stands at ready
    When the player sends a turn the support agent answers with an approval request
    Then the chat behaviour stands at awaiting approval with the action and its effect in the support agent's words
    And the only actions allowed are approving and cancelling

  Scenario: approving an approval request sends the decision once and gives the page the action's result and the reply
    Given the chat behaviour stands at awaiting approval
    When the player approves
    Then the decision is sent once for the conversation
    And the page is given the action's result and each part of the reply
    And the chat behaviour stands at ready

  Scenario: cancelling an approval request sends the decision and no action's result is given
    Given the chat behaviour stands at awaiting approval
    When the player cancels
    Then the decision is sent once for the conversation
    And the page is given each part of the reply and no action's result

  Scenario: a turn sent while an approval request awaits sends nothing
    Given the chat behaviour stands at awaiting approval
    When the page asks the chat behaviour to send a turn
    Then nothing is sent to the brand
    And the page is told the action is not allowed at awaiting approval

  Scenario: a page loaded while an approval request awaits learns of it at the next turn and stands at awaiting approval
    Given the player's conversation has an approval request awaiting
    And the page was loaded again
    When the player sends a turn and the brand refuses it naming the approval request
    Then the chat behaviour stands at awaiting approval with the action the refusal names

  Scenario Outline: a turn the brand refuses stands at a step naming the reason
    Given the chat behaviour stands at ready
    And the brand refuses the turn as "<code>"
    When the player sends a turn
    Then the chat behaviour stands at <step> with <fields>

    Examples:
      | code               | step        | fields                                |
      | agent_unavailable  | unavailable | the support agent's unavailable words |
      | too_many_turns     | refused     | the time to wait                      |
      | conversation_ended | ended       | the support agent's closing words     |
      | session_not_yours  | refused     | no fields                             |

  Scenario: a turn after a conversation ended starts a new conversation
    Given the chat behaviour stands at ended
    When the player sends a turn
    Then the turn is sent under a new session id

  Scenario: a turn refused by a guardrail gives the page the support agent's refusal words and the chat is ready again
    Given the chat behaviour stands at ready
    When the player sends a turn a guardrail refuses
    Then the page is given the support agent's refusal words as the reply
    And the chat behaviour stands at ready

  Scenario: a reply that stops arriving before the turn is answered ends the turn as interrupted
    Given the chat behaviour stands at answering
    When the reply stops arriving before the turn is answered
    Then the chat behaviour stands at ready with the turn marked interrupted
    And the turn is not sent again by itself

  Scenario: a brand whose support agent is off has its chat hidden
    Given the support agent of brand "guyanabet" is off
    When the player opens the chat
    Then the chat behaviour stands at unavailable

  Scenario: a site of a brand that does not run the support module offers no chat behaviour
    Given a brand "solo" running the module "core" and not the module "support"
    When a player site of brand "solo" is built
    Then the chat behaviour is not offered
