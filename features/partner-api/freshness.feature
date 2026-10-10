Feature: A partner can tell how fresh the data is
  A partner reconciling money reads the freshness and sees how far the brand's views lag its facts
  and whether they are degraded: rebuilding, catching up, or missing history a rebuild could not
  recover. A degraded answer is provisional, and the partner contract says so.

  Scenario: with the views caught up the lag is the time between the newest fact published and the newest applied, and degraded is false
    Given the views are caught up
    When a partner reads the freshness
    Then the lag is the time between the newest fact the brand published and the newest the view applied
    And degraded is false

  Scenario: a view rebuilding or with its consumer stopped reads as degraded with a growing lag
    Given a view rebuilding or with its consumer stopped
    When a partner reads the freshness
    Then degraded is true
    And the lag grows

  Scenario: a quiet brand with no new facts reads a lag near zero, because idleness is not lag
    Given a quiet brand with no new facts
    When a partner reads the freshness
    Then the lag is near zero

  Scenario: a rebuild started after the earliest retained fact stays degraded until an operator records the gap as accepted
    Given a rebuild started after the brand's earliest retained fact
    When a partner reads the freshness
    Then degraded stays true until an operator records that the gap is accepted
