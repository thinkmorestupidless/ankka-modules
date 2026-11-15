Feature: How fresh what the backoffice shows is
  Lists and figures read from a brand's views lag its facts; the backoffice says by how much, and
  says when what it shows is provisional rather than presenting late as empty.

  Scenario: a list read from a brand's views shows their freshness and says when it is provisional
    Given a brand whose views are catching up "5" minutes behind its facts
    When an operator lists the brand's players
    Then the list shows the views' freshness
    And the list is marked provisional
