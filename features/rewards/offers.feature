Feature: An operator creates an offer, a player claims it, and offers are targeted, listed and ordered
  An operator creates a deposit match offer on a brand. A player sees it among the offers they are
  eligible for, selects it, deposits, and the award lands as restricted funds with the wagering
  requirement the offer states. The brand's engagement targets an offer at a player; the player's
  eligible list shows it; an operator orders the brand's offers and sees every reward.

  Background:
    Given brand "goldvibe"

  Scenario: an operator creates a deposit match offer and it is listed active with every field as given
    Given an operator with a role for the brand
    When they create an offer of type "DEPOSIT_MATCH" with percentage "100", maximum value "50000", wagering multiplier "30" and minimum deposit "2000"
    Then the offer is listed as active for the brand with every field as given

  Scenario: a deposit match offer without a maximum value is refused naming it as required
    Given an operator with a role for the brand
    When they create an offer of type "DEPOSIT_MATCH" without a maximum value
    Then it is refused naming the maximum value as required

  Scenario: a pure free rounds offer is created without a maximum value
    Given an operator with a role for the brand
    When they create a free rounds offer without a maximum value
    Then the offer is created

  Scenario: a free rounds offer paying to restricted funds without a maximum convertible amount is refused naming it as required
    Given an operator with a role for the brand
    When they create a free rounds offer paying winnings to restricted funds without a maximum convertible amount
    Then it is refused naming the maximum convertible amount as required

  Scenario: selecting an active offer with no deposit required activates the reward at once with the offer's fixed amount
    Given an active offer with no deposit required
    When an eligible player selects it
    Then the reward activates at once with the offer's fixed amount and no deposit

  Scenario: selecting an active offer requiring a deposit leaves the reward pending
    Given an active offer requiring a deposit
    When an eligible player selects it
    Then the reward is pending

  Scenario: the next confirmed deposit activates a pending reward with the matched amount and clears the selection
    Given a pending selection of a deposit match offer with percentage "100" and maximum value "50000"
    When the player's next confirmed deposit of "5000" arrives
    Then the reward activates with restricted funds of "5000"
    And the pending selection is cleared

  Scenario: a deposit below the offer's minimum leaves the reward pending and is credited as real balance
    Given a pending selection of an offer with minimum deposit "2000"
    When a deposit of "1000" arrives
    Then the reward stays pending
    And the deposit is credited to real balance
    And the player may deposit again

  Scenario: a deposit after the deposit window expires the pending selection and awards nothing
    Given a pending selection older than the deposit window of "3" days from selection
    When a deposit arrives
    Then the reward is expired
    And nothing is awarded

  Scenario: the same deposit reported again under the same payment id awards nothing twice
    Given an active reward awarded on a deposit
    When the same deposit is reported again under the same payment id
    Then nothing is awarded twice

  Scenario: an offer the brand's engagement targeted at a player appears in their eligible list with the reason it is visible
    Given an offer the brand's engagement targeted at a player
    When the player lists their eligible offers
    Then the offer appears with the reason it is visible

  Scenario: targeting an offer at a player again changes nothing
    Given an offer the brand's engagement targeted at a player
    When it is targeted at the player again
    Then nothing changes

  Scenario: a player with rewards available or pending sees only those offers as selectable
    Given a player with rewards available or pending
    When they list their eligible offers
    Then only those offers are selectable

  Scenario: a player with no reward sees the offers visible to them by code or targeting
    Given a player with no reward available or pending
    When they list their eligible offers
    Then the offers visible to them by offer code or targeting are listed

  Scenario: offers reordered by an operator are seen by players in that order
    Given the brand's offers
    When an operator reorders them
    Then players see them in that order

  Scenario: an operator reading a player's rewards sees every reward with its state, face value, source and clocks
    Given a player with rewards
    When an operator reads the player's rewards
    Then every reward is listed with its state, its face value in major units, its source and its clocks

  Scenario: an operator reading the brand's rewards sees them across players, filterable by state
    Given rewards across the brand's players
    When an operator reads the brand's rewards
    Then every reward is listed across players, filterable by state

  Scenario: a player reading their reward state sees the active reward's balance, requirement and progress and their history
    Given a player with an active reward
    When they read their reward state
    Then the active reward's balance, wagering requirement and release progress are shown as the wallet reports them
    And their history of rewards is shown
