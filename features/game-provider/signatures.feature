Feature: A provider call is verified, and an unverifiable one is refused
  Every provider call carries the game provider's signature over its body. A missing, malformed or
  wrong signature is answered auth failed; a body that is not readable is refused as a bad request.

  Scenario: a provider call signed with the key matching the brand's provider signing key is served
    Given a brand holding the game provider's signing key
    When a provider call arrives signed under the matching key
    Then it is served

  Scenario Outline: a provider call with a missing or wrong signature is answered auth failed and nothing is read or written
    Given a brand holding the game provider's signing key
    When a provider call arrives with <signature>
    Then it is answered "auth_failed"
    And nothing is read or written

    Examples:
      | signature                          |
      | no signature                       |
      | a signature that is not readable   |
      | a signature over different content |

  Scenario Outline: a provider call whose body is not readable is refused as a bad request naming the call's own refusal word
    Given a brand holding the game provider's signing key
    When <call> arrives with a body that is not readable
    Then it is refused as a bad request naming "<word>"

    Examples:
      | call                  | word             |
      | a debit               | session_expired  |
      | a player profile read | player_not_found |

  Scenario: a rotated provider signing key verifies the next provider call without a redeploy
    Given a brand whose game provider signing key is rotated
    When the next provider call arrives
    Then it is verified against the new key without a redeploy
