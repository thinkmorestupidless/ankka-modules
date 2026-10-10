Feature: A player asks the support agent about their account and is answered from the brand's parts
  A signed-in player opens the chat and asks for their balance, their active rewards, their recent
  transactions or sessions, or their responsible gambling status. The support agent reads the
  answer from the owning part as itself, and streams a reply in the player's currency's major
  units.

  Background:
    Given a signed-in player of brand "goldvibe" whose support agent is on

  Scenario: a balance asked for is answered with the real and restricted balance in major units, read from the wallet as the support agent
    Given the player has a wallet in "GBP"
    When they ask the support agent for their balance
    Then the reply states the real balance and the restricted balance in major units of "GBP"
    And it was read from the wallet by the support agent's own identity

  Scenario: active rewards asked about are each named with their remaining wagering requirement and expiry
    Given the player has two active rewards
    When they ask the support agent about their bonuses
    Then the reply names each reward with its remaining wagering requirement and its expiry, read from the brand's rewards

  Scenario: recent activity asked for lists the last ten transactions with amount, kind and date
    Given the player has recent transactions
    When they ask the support agent for recent activity
    Then the reply lists the last ten with amount, kind and date, read from the wallet

  Scenario: limits asked about by a player on a break are answered with each limit, the break's end and whether they are excluded
    Given the player is on a break
    When they ask the support agent about their limits
    Then the reply states each limit, the break's end and whether they are excluded, read from responsible gambling

  Scenario: a question the support agent has no tool for is answered that it cannot help, naming the support chat, with no invented figure
    Given the player asks something the support agent has no tool for
    When the model answers
    Then the reply says it cannot help with that and names the support chat
    And it invents no figure

  Scenario: a balance asked for while the wallet does not answer in time is answered that it cannot be read right now, and the turn does not fail
    Given the wallet does not answer within the tool's time allowed
    When the player asks the support agent for their balance
    Then the reply says the balance cannot be read right now
    And the turn does not fail
