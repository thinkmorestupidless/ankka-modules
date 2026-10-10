Feature: A CRM campaign acts on a player through the brand's CRM webhook
  An operator's re-engagement CRM campaign posts an action to the brand's CRM webhook with the CRM campaign's
  delivery id. The brand verifies the signature and the time with its signing key, asks its
  rewards to award the offer to the player as the CRM origin, and answers the CRM campaign with the
  outcome. A send action writes an inbox item of the marketing class through the brand's player
  records, once per delivery id; a target action asks the brand's rewards to target an offer at
  the player.

  Scenario: a bonus action with a valid signature and time names a player and an active offer, and the brand's rewards are asked to award it as the CRM's
    Given a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window
    When a "grant_bonus" action names a player and an active offer
    Then the brand's rewards are asked to award it with source "customerio_webhook"
    And the answer is "ok"

  Scenario: a bonus action for an offer the player already holds a claim of is answered ok with already processed
    Given a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window
    When a "grant_bonus" action names a player who already holds a claim of the offer
    Then the answer is "ok" with "alreadyProcessed"

  Scenario: a bonus action for an offer the brand's rewards refuse carries their reason and awards nothing
    Given a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window
    When a "grant_bonus" action names an offer the brand's rewards refuse
    Then the answer carries the rewards' reason, "offer_not_found", "ineligible", "spin_value_not_offered" or "game_not_eligible"
    And nothing is awarded

  Scenario: a send action with a delivery id, a title and a body writes one inbox item of the marketing class, and a repeat writes nothing
    Given a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window
    When a "send_message" action arrives with a delivery id, a title and a body
    Then one inbox item of the marketing class is written through the brand's player records under that delivery id
    And it is refused as "marketing_suppressed", "account_closed" or "player_not_found" as the player records answer
    And a repeat with the same delivery id writes nothing

  Scenario: a send action without a delivery id is refused rather than given a fresh key
    Given a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window
    When a "send_message" action arrives without a delivery id
    Then it is refused as "delivery_id_required"

  Scenario Outline: a request whose signature is missing, malformed or wrong, or for a brand with no signing key, is refused as unauthenticated and does nothing
    Given <situation>
    When a request arrives
    Then it is refused as unauthenticated
    And nothing is done

    Examples:
      | situation                          |
      | a brand with a signing key for its CRM webhook and a request with no signature        |
      | a brand with a signing key for its CRM webhook and a request with a malformed signature |
      | a brand with a signing key for its CRM webhook and a request with a wrong signature     |
      | a brand with no signing key for its CRM webhook                                         |

  Scenario: an unknown action with a valid signature is refused as unknown
    Given a brand with a signing key for its CRM webhook, a valid signature over the request and a time within the window
    When a request arrives naming an action the brand does not know
    Then it is refused as "unknown_action"
