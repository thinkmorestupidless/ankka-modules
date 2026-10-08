Feature: A sub-affiliate subtree earns override
  A parent affiliate invites subs, approves, suspends and reactivates them, edits their plan and
  override percentage, and earns override on what every affiliate below them earns at each brand.
  Authority flows downward only.

  Scenario: each ancestor is written an override fact for its percentage of the source's earnings, recomputed as a delta
    Given an affiliate "T1" with an override of "5" percent over a sub "T2" with an override of "3" percent over a sub "T3"
    When "T3" earns "100000" of commission at brand "A" in a period
    Then "T2" is written an override fact of "3000" and "T1" one of "5000", each with "T3" as the source
    And each is recomputed as a delta when the earnings of "T3" change

  Scenario: a parent earns override at a brand where both hold a membership
    Given a parent and a sub who both hold memberships at brand "A" and only the parent at brand "B"
    When the sub earns at brand "A"
    Then the parent's override is written at brand "A"

  Scenario: a parent earns nothing at a brand where the sub has no membership
    Given a parent and a sub who both hold memberships at brand "A" and only the parent at brand "B"
    When facts for the sub arrive from brand "B"
    Then they are unattributed at brand "B"
    And no override is written

  Scenario: an invitation creates the sub pending under the parent with the parent's plan unless it says otherwise
    Given a parent
    When they invite a sub by email or share an invite link
    Then the sub's account is created pending under the parent
    And the sub inherits the parent's plan at each brand the parent names unless the invitation says otherwise
    And the parent is told when the sub applies

  Scenario: a parent's action on a sub applies within their own subtree and is audited as the parent's
    Given a parent
    When they approve, suspend or reactivate a sub, or change its plan or override
    Then it applies within their own subtree
    And it is audited as the parent's action

  Scenario: an operator's suspension of a sub stands over the parent
    Given a sub an operator has suspended
    When the parent reactivates the sub
    Then the operator's action stands
    And the parent sees it but cannot reverse it

  Scenario: an affiliate sees every descendant and nothing above or beside them
    Given an affiliate with a subtree
    When they read their subtree
    Then they see every descendant with status, plan, override and revenue for the period
    And they see nothing above or beside them

  Scenario: a request about an affiliate outside the subtree is refused
    Given an affiliate with a subtree
    When they ask about an affiliate outside their subtree
    Then it is refused

  Scenario: a parent chain that forms a cycle is visited once and the computation ends
    Given a corrupted parent chain that forms a cycle
    When override is computed
    Then each ancestor is visited once
    And the computation ends
