Feature: A programme reads a brand's facts
  The programme, another company's network, or a third-party network reads every fact the brand
  emits, by the network webhook or the feed, and never misses one or reads one twice without being
  able to tell. The programme is one network among any: nothing in the installation is allowed to
  read a brand's facts where the brand keeps them.

  Scenario: the programme receives a brand's facts by its network webhook as any network does, signed, within a minute
    Given the programme added to the brand as a network with a network webhook
    When the brand emits a fact
    Then the brand posts the fact to the programme's network webhook within a minute
    And the fact is signed as a fact posted to any network is

  Scenario: nothing in the installation is allowed to read a brand's attribution facts where they are kept
    Given the programme added to the brand as a network
    When the programme asks the installation for the brand's attribution facts
    Then it is refused
    And the brand allows its attribution facts to nothing in the installation

  Scenario: a network registered as a machine reads the same facts with its own reader and nothing else of the brand's
    Given a network registered as a machine and allowed to read the brand's attribution facts
    When it reads them from the installation with its own reader
    Then it reads the same facts from its own position
    And it is refused every other fact of the brand

  Scenario: the feed returns facts from a cursor in the order they were emitted, each page signed by the brand
    Given a network with no reader of its own and a machine's sign-in
    When it reads the brand's feed from a cursor
    Then it receives facts from that cursor in the order they were emitted
    And each page carries a signature over its facts under the brand's signing key
    And each page carries a cursor to continue from

  Scenario: reading the feed from the same cursor twice returns the same page
    Given a network that read the brand's feed from cursor "c1"
    When it reads the feed from cursor "c1" again
    Then it receives the same page

  Scenario: a network webhook posts each fact, retries for a day, then keeps it as an undelivered fact the operator can resend
    Given a network webhook the brand's operator created
    When a fact is emitted
    Then the brand posts the fact to the network webhook's address
    And the brand retries with backoff on failure for a day
    And after that the fact is kept as an undelivered fact the operator can see and resend

  Scenario: a reader can discard a fact whose id it has seen because the id and the content are the same both times
    Given a reader on any carrier
    When it receives a fact whose id it has seen
    Then it can discard the fact, because the id and the content are the same both times

  Scenario: facts from two brands about one affiliate name their brand, share the affiliate's id and differ in pseudonym
    Given an affiliate who sends traffic to two brands
    When both brands emit facts
    Then each fact names its brand
    And the affiliate's id is the same in both
    And the player pseudonyms differ
