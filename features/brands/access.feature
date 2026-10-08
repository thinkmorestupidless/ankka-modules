Feature: The right person reaches the right brand
  A player reaches only their own brand and their own wallet. An operator reaches the brands their
  roles name. One part of a brand calls another with no sign-in at all. A brand answers for itself
  and for no other brand.

  Scenario Outline: a player who signed in at their brand is served there and refused at another brand
    Given a player of brand "goldvibe" signed in
    When they read their own wallet at brand "<brand>"
    Then they are <outcome>

    Examples:
      | brand    | outcome |
      | goldvibe | served  |
      | spinvibe | refused |

  Scenario: an operator is refused at a brand their roles do not name however they signed in
    Given an operator whose roles name brand "goldvibe" only
    When they act as an operator at brand "spinvibe"
    Then they are refused, however they signed in

  Scenario: one part of a brand is admitted to another by its identity with no sign-in
    Given brand "goldvibe"
    When its game provider places a bet with its wallet
    Then it is admitted by its identity and never signs in

  Scenario: the shared payments is admitted to the deposit the brand allowed it
    Given brand "goldvibe" allows the shared payments to confirm deposits
    When the shared payments confirms a deposit for a player of brand "goldvibe"
    Then it is admitted

  Scenario: the shared payments is refused anything the brand did not allow it
    Given brand "goldvibe" allows the shared payments to confirm deposits
    When the shared payments reads the wallet of a player of brand "goldvibe"
    Then it is refused

  Scenario Outline: a public action is served with no sign-in
    Given brand "goldvibe"
    When a visitor <action> without signing in
    Then they are served

    Examples:
      | action                      |
      | registers                   |
      | lands on an affiliate's link |

  Scenario Outline: a payment provider's webhook is served only when its signature verifies
    Given brand "goldvibe"
    When a payment provider reports a payment outcome with a signature that <verifies>
    Then it is <outcome>

    Examples:
      | verifies        | outcome |
      | verifies        | served  |
      | does not verify | refused |

  Scenario: a registration is reported to the brand's player records and to no other part of the brand
    Given a person registered at brand "goldvibe"
    When the registration is reported to the brand
    Then the brand's player records learn of it, and no other part of the brand is told by the sign-in

  Scenario: a brand refuses to answer about any brand but its own
    Given brand "goldvibe"
    When it is asked about brand "spinvibe"
    Then it refuses, naming "goldvibe" as the brand it serves
