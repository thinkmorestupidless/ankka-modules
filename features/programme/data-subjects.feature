Feature: An affiliate is a data subject
  An affiliate's name, email, company, website and payment details are personal data under the
  affiliate; a player exists in the programme only as a pseudonym. An affiliate who leaves can be
  erased once the records finance must keep have aged out.

  Scenario: an affiliate's identity and payment fields are personal data under the affiliate
    Given an affiliate account
    When its identity and payment fields are written
    Then they are personal data under the affiliate
    And nothing the programme stores holds a readable name, email or payment details

  Scenario: a closed account's erasure waits for the retention hold and then erases identity while facts, payouts and audit remain
    Given a closed affiliate account
    When a manager files its erasure
    Then the request carries a retention hold until the end of the retention period finance names
    And when the retention hold lapses the account's identity fields read as erased
    And every fact, payout amount and audit record remains

  Scenario: a player read in the programme shows a pseudonym, a country and figures, and nothing that names the player
    Given facts about an attributed player
    When a manager or an affiliate reads the player
    Then they see the pseudonym the brand gave, the country and the figures
    And nothing that names the player

  Scenario: a player-closed fact makes the programme forget the pseudonym's country and click details and keep the amounts
    Given a brand that closes or erases a player
    When the "player-closed" fact arrives
    Then the programme forgets the pseudonym's country and click details
    And the programme keeps the facts' amounts
    And the programme cannot tell an erasure from a closure and does not need to
