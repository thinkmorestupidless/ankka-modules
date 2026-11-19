Feature: Brand guyanabet runs a reference site built on the player SDK
  The reference site is eitheror's own player site: the player SDK's site server and a few pages of
  eitheror's appearance, the one service of the module "player-site". Brand "guyanabet" runs it, in
  French first and English second, with no real money.

  Scenario: brand guyanabet runs the core, the casino, engagement, support and the player site
    When the modules tool renders brand "guyanabet" with the modules "casino", "engagement", "support" and "player-site"
    Then brand "guyanabet" runs the services of the modules "core", "casino", "engagement", "support" and "player-site"
    And brand "guyanabet" takes no real money

  Scenario: the reference site answers at the brand's player site address in French and in English
    Given brand "guyanabet" running the module "player-site"
    When a visitor whose browser asks for "fr" opens the brand's player site address
    Then the reference site answers in "fr"
    When the visitor chooses "en"
    Then the reference site answers in "en"

  Scenario: a visitor registers, signs in, deposits and plays at the reference site
    Given brand "guyanabet" running the modules "core", "casino" and "player-site" with a payment provider stub
    When a visitor registers, verifies their email address and signs in at the reference site
    And they deposit "5000" minor units of "EUR"
    And they pick a game
    Then the player's real balance is "5000" minor units of "EUR"
    And the play behaviour stands at playing

  Scenario: the reference site's pages call no service and hold no rule of a deposit or a launch
    When the reference site's pages are checked for what they call
    Then its pages reach the brand only through behaviours of the player SDK

  Scenario: a page of an old build finds its files during a rollout
    Given the reference site is being replaced by a new build
    When a page of the old build asks for one of its files
    Then the file is found

  Scenario: a reference site without the casino module is served without play
    Given a brand "solo" running the modules "core" and "player-site" and not the module "casino"
    When a visitor opens the brand's player site address
    Then the reference site answers
    And the play behaviour is not offered
