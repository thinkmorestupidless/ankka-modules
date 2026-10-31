Feature: An operator reviews documents and the KYC status follows the rules
  A compliance operator views each document and approves or rejects it with a reason code. In
  automatic mode the account becomes verified by itself once every category the brand's KYC policy
  requires has an approved latest document and the profile is complete; in manual mode nothing
  changes the KYC status but the operator's own decision. The operator can mark categories as
  required for one player, set the status directly, switch the mode, and find accounts waiting on
  their final decision.

  Background:
    Given a player of brand "goldvibe"

  Scenario: approving a pending document records the operator and time, and a second review of it is refused
    Given a pending document
    When an operator approves it
    Then the document is approved with the operator and the time recorded
    And a "player_kyc_document_reviewed" fact is published
    And a second approval or a rejection of the same document is refused as already reviewed

  Scenario: rejecting a pending document with a known reason code records the code
    Given a pending document
    When an operator rejects it with reason code "document_unclear"
    Then the document is rejected with that code

  Scenario: rejecting a document with an unknown reason code is refused
    Given a pending document
    When an operator rejects it with reason code "looks_odd"
    Then it is refused, because the code is not one of "document_unclear", "address_mismatch", "document_expired", "invalid_document" or "other"

  Scenario: in automatic mode an account whose required categories are approved with a complete profile becomes verified with the system as actor
    Given the player in automatic mode on a brand requiring identity and proof of address, with a complete profile
    When the latest identity front and the latest proof of address are both approved
    Then the KYC status is "verified"
    And a "player_kyc_verified" fact is published
    And an audit record names the system as the actor

  Scenario: in automatic mode an account with only the identity front approved stays pending
    Given the player in automatic mode on a brand requiring identity and proof of address, with a complete profile
    When only the latest identity front is approved
    Then the KYC status stays pending

  Scenario: an approved legacy identity document meets the identity requirement when no front exists
    Given a brand requiring identity only and an approved legacy identity document with no identity front
    When the KYC status is derived
    Then the identity requirement is met

  Scenario: an uploaded identity front stops the legacy identity document counting for anything
    Given a brand requiring identity only and an approved legacy identity document
    When an identity front is uploaded
    Then the legacy document no longer counts for anything, approved or rejected

  Scenario: a rejected latest document in any category makes the account rejected, required or not
    Given a category, required or not, whose latest document is rejected
    When the KYC status is derived
    Then the KYC status is "rejected"

  Scenario: a rejected document in the category other does not reject the account
    Given only a document in the category "other" is rejected
    When the KYC status is derived
    Then the KYC status is not "rejected" by it

  Scenario: any pending document makes the account pending even when every required category is approved
    Given every required category approved and a document pending, as a category's latest or in the category "other"
    When the KYC status is derived
    Then the KYC status is pending

  Scenario Outline: an account with every required category approved but an incomplete profile is pending and the player is told
    Given every required category approved and <gap>
    When the KYC status is derived
    Then the KYC status is pending
    And the player is told to complete their profile

    Examples:
      | gap                                               |
      | a required profile field missing                  |
      | a date of birth under eighteen years ago          |

  Scenario: a change to a required profile field returns a verified account to unverified until a document is reviewed after it
    Given the player is verified
    When they change a required profile field
    Then the KYC status returns to unverified with an audit record whose reason is "profile_changed"
    And the account is not verified again until a document is reviewed after the change

  Scenario: in manual mode approving every required document changes nothing
    Given the player in manual mode
    When every required document is approved
    Then the KYC status does not change

  Scenario: in manual mode the operator setting the account verified verifies it at once
    Given the player in manual mode with a complete profile
    When the operator sets the KYC status to verified
    Then the KYC status is "verified" at once

  Scenario: setting an account verified while the profile is incomplete is refused naming the missing fields
    Given the player in manual mode with an incomplete profile
    When the operator sets the KYC status to verified
    Then it is refused naming the missing fields

  Scenario: marking a category required for one player puts the account in manual mode and pending without affecting any other player
    Given the player in automatic mode
    When an operator marks a category required for this player
    Then the account is in manual mode
    And the KYC status is pending unless already pending or rejected
    And the player sees the category as required
    And no other player is affected

  Scenario: an account ready for final review is listed with the time it became ready
    Given a manual mode account whose every player-required category has an approved latest document, a complete profile and a KYC status other than verified
    When the queue is read with the "Ready for final KYC review" filter
    Then the account is listed with the time it became ready

  Scenario Outline: an account leaves the ready for final review list when the operator decides or changes its mode
    Given an account listed as ready for final review
    When the operator <action>
    Then it leaves the list
    And the time it became ready is cleared

    Examples:
      | action                           |
      | verifies it                      |
      | switches it to automatic mode    |
      | marks another category required  |

  Scenario Outline: an operator with a compliance or admin role for the brand views a document by a fifteen-minute address and the view is recorded
    Given an operator with the role "<role>" for the brand
    When they view a document of the player
    Then they are given a view address valid for "15" minutes
    And the view is recorded with their id

    Examples:
      | role             |
      | brand_admin      |
      | brand_compliance |

  Scenario: an operator of another brand is refused a document
    Given an operator of another brand
    When they view a document of the player
    Then it is refused

  Scenario Outline: an operator verifying or rejecting an account directly changes the status regardless of the documents, audited
    Given the player with documents in any state
    When an operator <action> the account directly
    Then the KYC status is "<status>" with the operator as actor
    And an audit record is written, regardless of the documents

    Examples:
      | action  | status   |
      | verifies | verified |
      | rejects  | rejected |
