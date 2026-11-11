Feature: The house provider's signing key is rotated by an operator, never by a clock
  The house provider signs every provider call with one signing key and publishes a key document
  naming its current key and, during a key rotation, its next. A key rotation is three operator acts:
  start, switch and withdraw. A brand that takes real money holds the key itself, so the operator
  copies the next key to every such brand before the switch.

  Background:
    Given a house provider serving two brands, one holding its key and one naming its key document

  Scenario: the key document names the current key and, during a rotation, the next key with their ids
    Given no key rotation in progress
    When the key document is read
    Then it names the current signing key by id and no next key

  Scenario: starting a rotation publishes the next key and changes nothing about how callbacks are signed
    Given no key rotation in progress
    When an operator starts a key rotation
    Then the key document names the current key and the next key by id
    And every provider call is still signed with the current key

  Scenario: switching signs every later callback with the next key and withdraws the old one from the document
    Given a key rotation started
    When an operator switches the key and then withdraws the old one
    Then every later provider call is signed with the next key
    And the key document names only the new key

  Scenario: a brand naming the key document admits a callback signed with the new key within its refresh bound
    Given a key rotation started and the brand naming the key document
    When the operator switches the key
    Then the brand admits provider calls signed with the new key within its configured bound

  Scenario: a brand marked real money whose entry names the key document is refused
    Given a brand entry naming the key document for a brand that takes real money
    When a launch for that brand arrives
    Then the house provider refuses it naming the brand entry
