package io.eitheror.casino.engagement.features

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.send.{Outcome, SendState}

import java.time.{Duration, Instant}

/**
 * features/engagement/tracks.feature (US1; FR-001, FR-002, FR-007). The player of the background has deposited once
 * before, so a deposit is not their first unless a scenario says the count is 0.
 */
class TracksFeatures extends EngagementFeatures("tracks.feature"):
  private var fact: f.DepositConfirmed = null
  private var kept: SendState          = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    fact = null
    kept = null

  private def calls = kit.crm.calls.filter(_.raw.contains(player))
  private def key   = sendKey("deposit_confirmed", fact)

  Given("a player of brand {string}") { (slug: String) =>
    assertEquals(kit.slug, slug)
    register()
    money(deposit(at = Instant.now().minusSeconds(1800)))
  }

  Given("the brand has a CRM configured, with a transactional id for the deposit trigger") { () =>
    assert(kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(kit.slug))
      .call(io.eitheror.casino.engagement.config.Configuration.get).invoke().transactional("DepositConfirmed").isDefined)
  }

  Given("the player's lifetime deposit count is {string}") { (n: String) =>
    assertEquals(n, "0")
    player = newPlayer()
    register()
  }

  Given("a {string} fact already consumed, with its send record") { (event: String) =>
    assertEquals(event, "deposit_confirmed")
    fact = deposit()
    publishMoney(fact)
    kept = settled(key)
    assertEquals(kept.outcome, Outcome.Accepted)
  }

  Given("a fact older than the brand's staleness bound at first sight") { () =>
    fact = deposit(at = Instant.now().minus(Duration.ofHours(25)))
  }

  Given("the brand has no CRM configured") { () => unconfigure() }

  Given("the CRM answers a retryable failure") { () => kit.crm.fail(503, 4) }

  When("a {string} fact arrives") { (event: String) =>
    assertEquals(event, "deposit_confirmed")
    fact = deposit()
    publishMoney(fact)
  }

  When("the same fact is delivered again") { () =>
    kit.crm.reset()
    publishMoney(fact)
  }

  When("it is consumed") { () => publishMoney(fact) }

  When("any fact arrives") { () =>
    fact = deposit()
    publishMoney(fact)
  }

  When("a send is attempted") { () =>
    fact = deposit()
    publishMoney(fact)
  }

  Then("one identify, one {string} track and one transactional send are made") { (event: String) =>
    val s = settled(key)
    assertEquals(s.outcome, Outcome.Accepted)
    assertEquals(kit.crm.identifies(player).size, 1)
    assertEquals(kit.crm.tracks(player).map(_.get("name").asText), Vector(event))
    assertEquals(kit.crm.sends.count(_.get("to").asText == s"$player@example.com"), 1)
  }

  Then("a send record names the fact id, the track, the time and the outcome") { () =>
    val s = settled(key)
    val r = s.request.get
    assertEquals((r.event, r.occurredAt, s.outcome), (Some("deposit_confirmed"), fact.occurredAt, Outcome.Accepted))
    assert(r.factId.nonEmpty && key.endsWith(r.factId), s"$key ${r.factId}")
    assertEquals(s.calls.map(_.kind), Vector("identify", "track", "transactional"))
    assert(s.calls.forall(_.at != null))
  }

  Then("no call is made to the CRM") { () =>
    Thread.sleep(2000)
    assertEquals(calls, Vector.empty)
  }

  Then("the send record is unchanged") { () => assertEquals(sendState(key), kept) }

  Then("a {string} track is made beside the {string} track, with the same data") { (first: String, event: String) =>
    settled(s"$first/${key.stripPrefix("deposit_confirmed/")}")
    settled(key)
    val tracks = kit.crm.tracks(player)
    assertEquals(tracks.map(_.get("name").asText).sorted, Vector(event, first).sorted)
    val data = tracks.map(t => kit.crm.attributes(t).filter((k, _) => Set("amount", "currency", "transaction_id")(k)).map((k, v) => k -> v.toString))
    assertEquals(data.distinct.size, 1, data)
  }

  Then("nothing is sent") { () =>
    settled(key)
    Thread.sleep(500)
    assertEquals(calls, Vector.empty)
  }

  Then("the send record says {string}") { (outcome: String) => assertEquals(settled(key).outcome, outcome) }

  Then("the brand's status names the gap") { () =>
    last = kit.request("GET", s"/v1/casino/backoffice/${kit.slug}/engagement/status", None, kit.asOperator())
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.get("configured").asBoolean, false, last.text)
  }

  Then("it is retried with backoff, four attempts in all") { () =>
    val s = settled(key)
    assertEquals(s.calls.find(_.kind == "identify").map(_.attempts), Some(4))
    assertEquals(kit.crm.identifies(player).size, 4)
  }

  Then("then it is a parked send with the last answer") { () =>
    val s = settled(key)
    assertEquals(s.outcome, Outcome.Parked)
    assertEquals(s.calls.head.status, Some(503))
    assert(s.calls.head.lastAnswer.isDefined)
  }
