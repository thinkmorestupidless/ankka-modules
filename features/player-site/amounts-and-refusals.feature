Feature: Amounts, refusals and language are one rule each at a player site
  Every amount the player SDK gives a page is a whole number of minor units with its currency,
  whatever form the service wrote it in. Every refusal reaches a page as a status, a code and named
  fields, whichever service wrote it. The page's language is the visitor's choice, else the
  browser's, else the site's first.

  Scenario Outline: a balance the wallet writes in major units is read exactly as minor units
    Given the wallet writes a real balance of "<written>" in "<currency>"
    When the player SDK reads the balance
    Then the page is given "<minor>" minor units of "<currency>"

    Examples:
      | written  | currency | minor  |
      | 1234.50  | EUR      | 123450 |
      | 0.05     | EUR      | 5      |
      | 10.00    | EUR      | 1000   |
      | 100      | JPY      | 100    |
      | 0.10     | USD      | 10     |

  Scenario Outline: a balance that does not fit its currency is refused as malformed and never rounded
    Given the wallet writes a real balance of "<written>" in "EUR"
    When the player SDK reads the balance
    Then the read is refused with the code "malformed_amount"
    And the page is given no balance

    Examples:
      | written |
      | 10.005  |
      | ten     |
      | 1e3     |

  Scenario Outline: an amount is shown by the page's language and the currency's own decimals
    Given the page's language is "<language>"
    When "<minor>" minor units of "<currency>" are shown
    Then the page shows "<shown>"

    Examples:
      | language | minor  | currency | shown       |
      | fr       | 123450 | EUR      | 1 234,50 €  |
      | en       | 123450 | EUR      | €1,234.50   |
      | fr       | 100    | JPY      | 100 JPY     |
      | pt       | 123450 | BRL      | R$ 1.234,50 |

  Scenario Outline: a refusal reaches the page in one form whichever service wrote it
    Given a service refuses a call in the form of <writer>
    When the refusal reaches the page
    Then it has a status, a code and named fields

    Examples:
      | writer                              |
      | the platform                        |
      | a service that writes words beside its code |
      | a service that writes only its code |

  Scenario: a refusal the platform writes as a code and an explanation is read as its code
    Given the platform refuses a call as "wrong_brand: this is brand guyanabet"
    When the refusal reaches the page
    Then its code is "wrong_brand"

  Scenario: a refusal with a code the player SDK does not know is the unknown refusal and the service's text is not the player's
    Given a service refuses a call with a code the player SDK does not know
    When the refusal reaches the page
    Then its code is "unknown" with the status the service answered
    And the page is given no text of the service's

  Scenario: every refusal code a behaviour can meet has words in each language the reference site offers
    When the reference site is built
    Then every refusal code the behaviours can meet has words in "fr" and in "en"

  Scenario Outline: the page's language is the visitor's choice, else the browser's, else the site's first
    Given a player site offering "fr" then "en"
    And a visitor whose browser asks for "<browser>" and whose kept choice is "<choice>"
    When they arrive
    Then the page's language is "<language>"

    Examples:
      | browser | choice | language |
      | en      | none   | en       |
      | de      | none   | fr       |
      | en      | fr     | fr       |
      | fr      | en     | en       |

  Scenario: a text missing in a language is shown in the site's first language
    Given a player site offering "fr" then "en" whose "en" words lack one text
    When the page shows that text in "en"
    Then the "fr" words are shown
