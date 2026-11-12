package io.eitheror.casino.publicapi.features

import io.eitheror.casino.publicapi.it.{FactSet, Keys, MadeKey, Reply, Seeded, SharedKit}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/public-api/access.feature (T038): two brands in two instances. */
class AccessFeatures extends PublicApiFeatures("access.feature"):
  private var key: Option[String] = None
  private var answers: Vector[Reply] = Vector.empty

  private def caller: MadeKey = Seeded.none

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    answers = Vector.empty

  Given("a caller holding an access key of brand {string}") { (brand: String) =>
    assertEquals(brand, kit.brand); key = Some(caller.secret); val _ = Seeded.set
  }
  When("it lists players") { () => last = kit.v1("/players", key) }
  Then("every player returned belongs to brand {string}") { (brand: String) =>
    assertEquals(last.status, 200)
    val rows = last.json.path("data").elements().asScala.toVector
    assert(rows.nonEmpty)
    assert(rows.forall(_.path("brandId").asText() == brand))
  }

  When("it asks for a record of brand {string} by id") { (brand: String) =>
    val other = SharedKit.other
    assertEquals(brand, other.brand)
    val pl = FactSet.id()
    other.publish(io.eitheror.casino.contract.facts.CasinoPlayers, FactSet.registered(pl, other.now))
    val k = Keys.noFields(other)
    other.eventually("the other brand's player")(Option.when(other.v1(s"/players/$pl", Some(k.secret)).status == 200)(()))
    answers = Vector(kit.v1(s"/players/$pl", key), kit.v1(s"/players/${FactSet.id()}", key))
  }
  Then("it is told the record is not found") { () => assertEquals((answers(0).status, answers(0).error), (404, "not_found")) }
  Then("the answer is the same as for an id that does not exist") { () =>
    assertEquals((answers(0).status, answers(0).text), (answers(1).status, answers(1).text))
  }

  Given("a request with no access key") { () => key = None }
  Given("a request with a malformed access key") { () => key = Some("Bearer-ish") }
  Given("a request with an unknown access key") { () => key = Some("ak_aaaaaaaaaaaaaaaa_" + "A" * 43) }
  Given("a request with an access key of brand {string}") { (brand: String) =>
    assertEquals(brand, SharedKit.other.brand); key = Some(Keys.noFields(SharedKit.other).secret)
  }
  Given("a request with a disabled access key") { () => key = Some(Keys.disabled(kit).secret) }
  Given("a request with a deleted access key") { () => key = Some(Keys.deleted(kit).secret) }
  Given("a request with an expired access key") { () =>
    val k = Keys.expiring(kit); kit.moveClock(61.seconds); key = Some(k.secret)
  }
  When("it arrives at brand {string}") { (brand: String) =>
    assertEquals(brand, kit.brand); last = kit.v1("/players", key)
  }
  Then("it is refused") { () => assert(Set(401, 403)(last.status), last.text) }
  Then("no data is returned") { () =>
    assertEquals(last.json.fieldNames().asScala.toSet -- Set("error", "message"), Set.empty[String])
  }

  When("it sends anything but a read") { () =>
    answers = Vector("POST", "PUT", "PATCH", "DELETE").map(m => kit.v1("/players", key, m))
  }
  Then("it is refused as an invalid request") { () =>
    val all = if answers.nonEmpty then answers else Vector(last)
    all.foreach(r => assertEquals(r.error, "invalid_request", r.text))
  }
  When("it asks for a record by a malformed id") { () => last = kit.v1("/players/not-an-id", key) }
