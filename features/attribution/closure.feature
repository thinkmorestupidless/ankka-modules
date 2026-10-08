Feature: A player is closed or erased
  When a player's account closes, the programme learns it so it can stop expecting activity. When
  the player is erased, the brand's facts about them stand, because they were never personal.

  Scenario Outline: closing, permanently self-excluding or erasing an attributed player emits one player-closed fact with a category and no reason
    Given an attributed player
    When their account is <how>
    Then one "player-closed" fact is emitted with the category "<category>" and never a reason text

    Examples:
      | how                       | category      |
      | closed                    | closed        |
      | permanently self-excluded | self-excluded |
      | erased                    | closed        |

  Scenario: an erased player's attribution record keeps its binding and every personal field reads as erased
    Given an erased player
    When the brand's attribution record for the player is read
    Then the binding remains: network, affiliate, link, sub-ids, click, pseudonym and dates
    And every personal field of the player reads as erased

  Scenario: facts already emitted about an erased player are unchanged
    Given facts already emitted about an erased player
    When a reader reads them again
    Then they are unchanged
