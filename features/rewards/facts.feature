Feature: Every change to a reward is a fact
  Each reward transition is a fact, so the brand's attribution, engagement, partner facts and
  reporting learn of it without a call.

  Background:
    Given a player of brand "goldvibe"

  Scenario: a reward activating is published with the reward, the offer, the amount, the currency, the source and the code
    Given a pending reward of the player
    When it activates
    Then a "bonus_granted" fact carries the reward, the offer, the amount, the currency, the source and the code when one was used

  Scenario Outline: a reward transition is published as the matching fact
    Given a reward of the player
    When it is <transition>
    Then a "<kind>" fact is published

    Examples:
      | transition                 | kind                |
      | cancelled                  | bonus_cancelled     |
      | forfeited                  | bonus_forfeited     |
      | revoked                    | bonus_cancelled     |
      | converted                  | bonus_completed     |
      | completed                  | bonus_completed     |
      | expired                    | grant_expired       |
      | given as free rounds       | free_spins_granted  |
      | finalised as free rounds   | free_spins_settled  |

  Scenario: the same transition applied twice publishes one fact
    Given a reward of the player
    When the same transition is applied twice
    Then one fact is published
