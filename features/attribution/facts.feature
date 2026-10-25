Feature: A player's money and play reach the programme as facts
  After registration the brand emits what a programme needs to pay commission: the first deposit,
  every deposit, each day's GGR, the cost of rewards, chargebacks, withdrawals and their reversals,
  and closure. Every amount is a whole number of minor units in the player's currency; nothing is a
  plan, a rate or a commission.

  Scenario: a player's first captured deposit emits a first-deposit fact and a deposit fact, the first once in the player's life
    Given an attributed player with no deposit
    When a deposit is captured
    Then a "first-deposit" fact and a "deposit" fact are emitted, each with the amount, the currency and the payment reference
    And the "first-deposit" fact is emitted once in the player's life

  Scenario: a later captured deposit emits only a deposit fact
    Given an attributed player who has deposited before
    When a deposit is captured
    Then only a "deposit" fact is emitted

  Scenario: one daily GGR fact is emitted per player when the brand's reporting day closes
    Given an attributed player who played during a day
    When the brand's reporting day closes
    Then one "ggr-daily" fact is emitted for the player and the day
    And it holds total stake, total win and their difference as signed minor units

  Scenario: a round of a closed day that settles after the close emits a GGR adjustment for that day
    Given a round of a closed day that settles after the close
    When it settles
    Then a "ggr-adjustment" fact is emitted for that day with the difference
    And the day's earlier fact is not emitted again

  Scenario: a network that opted into the round stream receives a round fact at settlement and the daily fact at the close
    Given a network whose configuration at the brand opts into the round stream
    When a round settles
    Then a "ggr-round" fact is emitted with the round's stake, win and GGR
    And the daily fact is still emitted at the close

  Scenario: a network with the default configuration receives the daily fact and no rounds
    Given a network with the default configuration at the brand
    When a round settles
    Then no "ggr-round" fact is emitted for it
    And the daily fact is emitted at the close

  Scenario Outline: what happens to an attributed player's money is emitted as the matching fact with its amount and reference
    Given an attributed player
    When <happening>
    Then a "<kind>" fact is emitted with its amount where there is one and the reference of the thing it is about

    Examples:
      | happening                            | kind                 |
      | restricted funds are awarded         | bonus-cost           |
      | a deposit is charged back            | chargeback           |
      | a withdrawal is requested            | withdrawal-requested |
      | a withdrawal is completed            | withdrawal-completed |
      | a withdrawal fails                   | withdrawal-failed    |
      | a withdrawal is reversed             | withdrawal-reversed  |

  Scenario: no fact carries a personal field
    Given a fact of any kind
    When a reader inspects it
    Then it holds no name, address, email, date of birth, document or other personal field
