Feature: A player submits documents and sees where they stand
  A player opens their documents page and sees the six categories, each with its own status and its
  own upload action. They upload a file for a category, it shows as pending review, and once an
  operator has decided they see approved, or rejected with the reason in their own language and a
  way to upload a replacement. They always see the account's KYC status.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a player with no documents sees all six categories with upload actions and the required ones marked
    Given the player has no documents
    When they open their documents page
    Then all six categories are listed, each with no document and an upload action
    And the categories the brand's KYC policy requires are marked as required

  Scenario: asking to upload a file for a category gives an upload address valid for an hour and a document id, and the upload lands under the player's own prefix
    Given the category "identity_front"
    When the player asks to upload a file of type "JPEG", "PNG" or "PDF" for it
    Then they are given an upload address valid for one hour and a document id
    And the upload lands under the player's own prefix in the brand's document store and nowhere else

  Scenario: confirming a completed upload shows the category pending review and locks the document
    Given an upload that completed
    When the player confirms it
    Then the category shows pending review
    And the document cannot be changed or removed by the player
    And a "player_kyc_submitted" fact is published

  Scenario Outline: a category whose latest document is pending or approved refuses another upload as locked
    Given a category whose latest document is <state>
    When the player asks to upload another for it
    Then it is refused as locked

    Examples:
      | state    |
      | pending  |
      | approved |

  Scenario: the category other is never locked and every upload to it is a document of its own
    Given the category "other" with a document pending
    When the player asks to upload another for it
    Then it is accepted as a document of its own

  Scenario: a replacement for a rejected document shows the category pending review and hides the rejection reason
    Given a category whose latest document was rejected
    When the player uploads a replacement
    Then the category shows pending review
    And the rejection reason is no longer shown

  Scenario: a rejected document's reason is shown as one of the reason codes in the player's language and never as free text
    Given a rejected document
    When the player views its category
    Then the reason is one of the five reason codes rendered in the player's language
    And never a free text

  Scenario: a player viewing one of their documents is given a view address valid for fifteen minutes to it alone, and the view is recorded
    Given a document of the player
    When they ask to view it
    Then they are given a view address valid for "15" minutes to that document and no other
    And the view is recorded

  Scenario: a player asking to view another player's document is refused
    Given a document of another player
    When the player asks to view it
    Then it is refused

  Scenario Outline: a file of a type or size the brand does not take is refused naming the rule
    Given a file that is <fault>
    When the player asks to upload it
    Then it is refused naming the rule

    Examples:
      | fault                                   |
      | of a type other than JPEG, PNG or PDF   |
      | larger than the brand's limit           |
