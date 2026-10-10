Feature: An operator suspends, reinstates, excludes, closes and tiers a player
  A brand operator suspends a player with a reason, reinstates them, sets a tier, writes notes, and
  closes the account; an exclusion reaches the player status through responsible gambling. Each
  transition is a fact other parts act on: a suspension ends sessions and bars play; a closure ends
  everything and starts the retention clock.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a suspension with a reason ends every session, revokes the realm's sessions and carries the previous and new status
    Given the player is active and signed in
    When an operator suspends them with a reason
    Then the player status is "suspended"
    And every session ends with reason "suspended"
    And the realm's sessions are revoked
    And the "player_suspended" fact carries the previous and new status, the actor and the reason

  Scenario: a reinstatement makes a suspended player active
    Given the player is suspended
    When an operator reinstates them
    Then the player status is "active"
    And a "player_reinstated" fact is produced

  Scenario: an exclusion applied through responsible gambling sets the status, keeps the exclusion time and ends every session
    Given the player is active
    When an operator excludes them through responsible gambling, which tells the brand's player records to set the status
    Then the player status is "self_excluded"
    And the exclusion time is kept under the name every reader uses
    And every session ends
    And a "player_self_excluded" fact is produced

  Scenario: an operator has no exclusion action on the player records themselves
    Given the player is active
    When an operator excludes them on the player records directly
    Then it is refused

  Scenario: a tier set on a player at no tier carries an empty previous tier and names the operator in the audit record
    Given the player is at no tier
    When an operator sets tier "gold"
    Then the "vip_tier_changed" fact carries an empty previous tier and "gold"
    And the audit record names the operator

  Scenario: a closure ends everything, disables the realm user and is produced with actor and reason
    Given the player is active
    When an operator closes the account
    Then the player status is "closed"
    And the closure time is kept
    And every session ends
    And the realm user is disabled
    And a "player_closed" fact is produced with the actor and the reason
    And the brand's attribution emits its closure fact from it

  Scenario: a closed player's sign-in is refused and their wallet balances are untouched
    Given the player is closed
    When they sign in
    Then they are refused as "account_restricted"
    And their wallet balances are untouched by the closure

  Scenario: an operator without the brand's role is refused any action on its player however they signed in
    Given an operator without the brand's role
    When they act on the player
    Then they are refused, however they signed in

  Scenario: a pinned note is personal data under the player, first in the operator's view and invisible to the player
    Given an operator of the brand
    When they write a note about the player and pin it
    Then the note is personal data under the player
    And it is pinned first in the operator's view
    And it is invisible to the player
