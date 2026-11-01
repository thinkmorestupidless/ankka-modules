Feature: Another company's brand runs on the programme
  An operator of the installation adds a hosted brand under a contract signed outside the platform.
  Its managers run it like any brand, its facts arrive over the feed, it sees nothing of other
  companies' brands, and eitheror invoices it each month from the programme's usage report.

  Scenario: an operator of the installation adds a hosted brand with its configuration, programme page, default plan and first manager
    Given a contract signed with another company for brand "H"
    When an operator of the installation adds hosted brand "H" with its configuration, programme page, default plan and first manager
    Then brand "H" is in the programme
    And its first manager is provisioned with the manager role and the role of brand "H"
    And brand "H" is issued the credentials its feed delivers facts under

  Scenario: a hosted brand's facts arrive over the feed and become commission as any brand's do
    Given hosted brand "H" and an affiliate with an active membership at brand "H"
    When brand "H" delivers a "registration" fact and a "first-deposit" fact over the feed
    Then the player is attributed and CPA is written exactly as for a brand in the installation

  Scenario: a manager of a hosted brand sees nothing of an affiliate's membership at another company's brand
    Given an affiliate with active memberships at hosted brand "H" and at brand "A"
    When a manager of brand "H" reads the affiliate
    Then they see the affiliate's membership, figures and payouts at brand "H"
    And nothing of the affiliate at brand "A"

  Scenario: the usage report gives a brand's month per currency and no fee
    Given facts, memberships and payouts at hosted brand "H" during a month
    When an operator of the installation reads the usage report for brand "H" and that month
    Then it gives the facts received, the active memberships, the commission recorded and the commission paid, each per currency
    And it gives no fee

  Scenario: a manager cannot read the usage report
    Given a manager of hosted brand "H"
    When they ask for the usage report for brand "H"
    Then it is refused
