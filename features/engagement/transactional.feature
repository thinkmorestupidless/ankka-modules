Feature: The brand's player records send a verification email through engagement
  A visitor registers. The brand's player records ask engagement to send the address verification
  email with the address they minted, naming the trigger and the recipient. Engagement sends the
  brand's transactional send for that trigger, or the built-in fallback template when none is
  configured, records the send under the request's key, and answers whether it was accepted. A
  repeat with the same key sends nothing and answers the first outcome.

  Background:
    Given brand "goldvibe"

  Scenario: a request naming the email address verification trigger makes one transactional send with the verification address and answers accepted
    Given a request from the brand's player records naming the trigger "EmailAddressVerification", a recipient and a verification address
    When it is received
    Then one transactional send is made with the verification address and the brand name as data
    And the answer carries the send id and "accepted"

  Scenario: the same request again under the same key makes no send and answers the first outcome
    Given a request from the brand's player records already served under its key
    When the same request is received again under that key
    Then no send is made
    And the answer is the first

  Scenario: a trigger with no transactional id and no fallback sends nothing and answers not configured
    Given a trigger with no transactional id configured and no fallback template
    When a send is requested for it
    Then nothing is sent
    And the answer says "not_configured"

  Scenario: a request from a caller that is not a part of the brand is refused
    Given a caller that is not a part of the brand
    When it requests a send
    Then it is refused
