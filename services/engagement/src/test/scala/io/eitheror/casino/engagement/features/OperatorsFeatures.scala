package io.eitheror.casino.engagement.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.config.{Secrets, Seed}
import io.eitheror.casino.engagement.it.{FakeCrm, Reply, SseReader}
import io.eitheror.casino.engagement.profile.Suppression
import io.eitheror.casino.engagement.send.Outcome

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/engagement/operators.feature (US7; FR-004, FR-005, FR-023, FR-025): the backoffice and console routes as an
 * operator with the brand's role calls them. A parked send is a deposit's send with Customer.io failing all four
 * attempts; the player has deposited before, so the deposit makes one send and not a first deposit's too.
 */
class OperatorsFeatures extends EngagementFeatures("operators.feature"):
  private var key       = ""
  private var streams   = Vector.empty[SseReader]
  private var online    = Vector.empty[String]
  private var seen      = 0
  private var newAppKey = ""
  private var answered  = Vector.empty[Reply]

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    key = ""
    streams = Vector.empty
    online = Vector.empty
    seen = 0
    answered = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    streams.foreach(_.close())
    kit.configure(EngagementFeatures.Triggers): Unit
    super.afterEach(context)

  private def backoffice(rest: String) = s"/v1/casino/backoffice/${kit.slug}$rest"
  private def config(rest: String = "")  = s"/v1/casino/crm-config/${kit.slug}$rest"
  private def encoded(k: String)         = URLEncoder.encode(k, UTF_8)
  private def get(path: String): Reply   = kit.request("GET", path, None, kit.asOperator())
  private def post(path: String, body: Option[JsonNode] = None): Reply =
    kit.request("POST", path, body.map(Json.render), kit.asOperator())

  private def sent: Int = kit.crm.tracks(player).size + kit.crm.sends.count(_.path("to").asText.contains(player))

  private def parkedKeys: Vector[String] =
    get(backoffice("/parked-sends?search=deposit")).json.path("sends").elements.asScala.toVector.map(_.path("sendId").asText)

  /** A registered player with an earlier deposit, then a deposit whose send Customer.io fails four times. */
  private def parked(): Unit =
    register()
    money(deposit(at = Instant.now().minusSeconds(1800)))
    kit.crm.fail(503, 4)
    val d = deposit()
    publishMoney(d)
    key = sendKey("deposit_confirmed", d)
    assertEquals(settled(key).outcome, Outcome.Parked)
    eventually("listed as parked")(Option.when(parkedKeys.contains(key))(()))

  Given("brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // A player's sends.

  Given("sends for a player") { () =>
    register()
    for i <- 0 until 3 do
      val d = deposit(at = Instant.now().minusSeconds(60L * (3 - i)))
      publishMoney(d)
      settled(sendKey("deposit_confirmed", d)): Unit
    eventually("four sends listed")(Option.when(get(backoffice(s"/players/$player/sends")).json.path("total").asLong >= 4)(()))
  }

  When("an operator with the brand's role reads them") { () => last = get(backoffice(s"/players/$player/sends")) }

  Then("each shows its track, class, channel, time, outcome and reason, newest first, paged") { () =>
    assertEquals(last.status, 200, last.text)
    val rows = last.json.path("sends").elements.asScala.toVector
    assertEquals((last.json.path("page").asInt, last.json.path("total").asLong), (1, rows.size.toLong))
    for r <- rows; field <- Vector("sendId", "trigger", "event", "class", "channel", "time", "outcome", "reason") do
      assert(r.has(field), s"$field in $r")
    assertEquals(rows.map(_.path("event").asText).toSet, Set("deposit_confirmed", "first_deposit_confirmed"))
    assert(rows.forall(r => r.path("class").asText.nonEmpty && r.path("channel").asText.nonEmpty), rows.toString)
    val times = rows.map(r => Instant.parse(r.path("time").asText))
    assertEquals(times, times.sortWith(_.isAfter(_)))
  }

  // Parked sends.

  Given("a parked send after retries") { () => parked() }

  When("an operator retries it and the CRM accepts") { () => last = post(backoffice(s"/parked-sends/${encoded(key)}/retry")) }

  Then("the send record's outcome becomes {string}") { (outcome: String) =>
    assertEquals((last.status, last.str("outcome")), (200, outcome), last.text)
    assertEquals(sendState(key).outcome, outcome)
  }

  Then("it is no longer a parked send") { () =>
    eventually("gone from the parked sends")(Option.when(!parkedKeys.contains(key))(()))
  }

  When("an operator discards it with a reason") { () =>
    last = post(backoffice(s"/parked-sends/${encoded(key)}/discard"), Some(Json.obj("reason" -> "a duplicate of a campaign")))
  }

  Then("the record names who discarded it and why") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals((last.str("outcome"), last.str("discardedBy"), last.str("discardReason")),
      (Outcome.Discarded, "op-1", "a duplicate of a campaign"))
  }

  Given("a parked send of the marketing class for a player who has since self-excluded or withdrawn consent") { () =>
    parked()
    players(f.RgCaseOpened(player, s"case-$player", Suppression.SelfExclusionCase, "player", None, Instant.now().minusSeconds(5)))
    seen = sent
  }

  When("an operator retries it") { () => last = post(backoffice(s"/parked-sends/${encoded(key)}/retry")) }

  // The identify is still made, saying the player is suppressed (FR-011); the send is the track and any email.
  Then("no send is made") { () => assertEquals(sent, seen) }

  Then("the send record's outcome becomes {string} with the reason") { (outcome: String) =>
    assertEquals((last.status, last.str("outcome"), last.str("reason")), (200, outcome, Suppression.SelfExcluded), last.text)
  }

  Then("the refusal is an audit record naming the operator") { () =>
    val r = eventually("the refusal audited")(kit.facts(f.PlatformAudit).collectFirst {
      case a: f.AuditRecord if a.subjectId == player && a.action == "engagement.send.refused" => a
    })
    assertEquals((r.actorKind, r.actorId, r.reasonCode), ("operator", "op-1", Some(Outcome.MarketingSuppressed)))
  }

  // Configuration.

  Given("the brand's CRM configuration page") { () => assertEquals(get(config()).status, 200) }

  When("an operator saves a new app key") { () =>
    newAppKey = s"app-${player.drop(2)}-new"
    last = kit.request("PUT", config(), Some(Json.render(Json.obj("appApiKey" -> newAppKey))), kit.asOperator())
    assertEquals(last.status, 200, last.text)
  }

  Then("it is stored in the secret store") { () => assertEquals(kit.secrets.get(Secrets.app(kit.slug)), Some(newAppKey)) }

  Then("it is read back masked, as its first three characters and asterisks") { () =>
    assertEquals(get(config()).str("appApiKey"), newAppKey.take(3) + "*" * (newAppKey.length - 3))
    assert(!get(config()).text.contains(newAppKey))
  }

  Then("a masked value sent back unchanged preserves the stored secret") { () =>
    val shown = get(config()).json
    last = kit.request("PUT", config(), Some(Json.render(shown)), kit.asOperator())
    assertEquals(last.status, 200, last.text)
    assertEquals(kit.secrets.get(Secrets.app(kit.slug)), Some(newAppKey))
    assertEquals(kit.secrets.get(Secrets.track(kit.slug)), Some(FakeCrm.TrackKey))
  }

  Given("the brand's CRM credential saved") { () => assert(get(config()).str("siteId").nonEmpty) }

  When("an operator seeds the track list") { () =>
    last = post(config("/seed"))
    answered = Vector(last)
  }

  Then("one identify and one track per trigger reach the seed person {string}") { (person: String) =>
    assertEquals((kit.crm.identifies(person).size, kit.crm.tracks(person).size), (Seed.events.size, Seed.events.size))
    assertEquals(kit.crm.tracks(person).map(_.path("name").asText).toSet, Seed.events.map(_._2).toSet)
  }

  Then("the answer counts them") { () =>
    assertEquals((last.status, last.json.path("seeded").asInt, last.str("person")), (200, Seed.events.size, Seed.person(kit.slug)))
  }

  Then("seeding twice updates the same person") { () =>
    val again = post(config("/seed"))
    assertEquals(again.json, answered.head.json)
    val person = Seed.person(kit.slug)
    assertEquals(kit.crm.identifies(person).size, 2 * Seed.events.size)
    assertEquals(kit.crm.entities.map(_.path("identifiers").path("id").asText).toSet, Set(person))
  }

  // Presence.

  Given("players with open push connections") { () =>
    online = Vector(newPlayer(), newPlayer())
    streams = online.map { p =>
      val s = SseReader(kit.baseUrl + "/v1/stream", kit.bearer(s"player|$p").toMap)
      s.next("ready", 10.seconds): Unit
      s
    }
  }

  When("an operator reads presence") { () =>
    last = eventually("both online")(Option(get(backoffice("/presence"))).filter { r =>
      val listed = r.json.path("online").elements.asScala.map(_.path("playerId").asText).toSet
      online.forall(listed)
    })
  }

  Then("each is listed as online with the time they came online") { () =>
    val rows = last.json.path("online").elements.asScala.toVector.filter(r => online.contains(r.path("playerId").asText))
    assertEquals(rows.size, 2)
    for r <- rows do
      val since = Instant.parse(r.path("since").asText)
      assert(JDuration.between(since, Instant.now()).abs.toSeconds < 60, r.toString)
    assert(last.json.path("count").asLong >= 2)
  }
