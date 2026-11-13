package io.eitheror.casino.provider.edge.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.provider.edge.ProviderTestKit
import io.eitheror.casino.provider.edge.ProviderTestKit.staff
import io.eitheror.casino.provider.edge.registry.GameEntity
import com.thinkmorestupidless.ankka.core.EntityId

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/games/brands.feature with two `FakeBrand`s, `goldvibe` and `spinvibe` (US3). The kit's
 * service is shared by every suite in the JVM, so each scenario leaves goldvibe's entry as the kit
 * wrote it.
 */
class BrandsFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/games/brands.feature") with LogCapturing:

  override val munitTimeout = 2.minutes

  private val mapper = ObjectMapper()
  private def kit    = ProviderTestKit.kit
  private def game   = kit.game
  private def json(text: String): JsonNode = mapper.readTree(text)

  private val installation = staff("games")
  private val goldOperator = staff("brand_goldvibe")

  private var ticket = ""
  private var player = ""
  private var answer: ProviderTestKit.Answer = null
  private var answers: Map[String, ProviderTestKit.Answer] = Map.empty

  override def afterEach(context: AfterEach): Unit =
    kit.restoreGoldvibe()
    kit.removeEntry("spinvibe")

  private def fresh(): Unit =
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    player = s"p-$scenarioId-${System.nanoTime()}"

  private def putEntry(slug: String, address: String, games: Seq[String] = Nil, token: String = installation): ProviderTestKit.Answer =
    kit.asStaff("PUT", s"/v1/provider/brands/$slug", token, Some(kit.entryBody(slug, address, games)))

  private def listed(a: ProviderTestKit.Answer): Vector[String] = json(a.body).get("games").elements().asScala.map(_.get("code").asText()).toVector

  // ---- the table -------------------------------------------------------------------------------

  Given("a house provider with no brand entry for {string}") { (slug: String) =>
    kit.start()
    game.up()
    assertEquals(game.registerGame("test-slot", "slots").status, 200)
    kit.removeEntry(slug)
    fresh()
  }

  When("an operator adds a brand entry for {string} with its address, its site, its credential, the language {string} and the games it may run") {
    (slug: String, language: String) =>
      assertEquals(language, "pt")
      answer = putEntry(slug, kit.brand.address, Seq("test-slot"))
      assertEquals(answer.status, 200, answer.body)
  }

  Then("a launch from {string} signed with that credential is admitted") { (slug: String) =>
    val a = kit.launch(ticket, player, "test-slot", site = slug)
    assert(a.json.has("game_url"), a.body)
    assertEquals(kit.session(ticket).brand, slug)
  }

  Then("the change applies with no redeploy") { () =>
    // The write reached the cache the launches read: the launch above was admitted by it, and the
    // entry reads back from the route as written.
    val read = kit.asStaff("GET", "/v1/provider/brands/goldvibe", installation)
    assertEquals(read.status, 200, read.body)
    assertEquals(read.json.get("games").elements().asScala.map(_.asText()).toVector, Vector("test-slot"))
  }

  When("a launch for {string} arrives") { (slug: String) => answer = kit.launch(ticket, player, "test-slot", site = slug) }

  Then("the house provider refuses it") { () =>
    if answer != null then assertEquals(answer.json.get("error").asText(), "auth_failed", answer.body)
    else answers.values.foreach(a => assert(a.status >= 400 || a.json.has("error"), a.body))
  }

  // ---- a brand's games ---------------------------------------------------------------------------

  Given("a brand entry for {string} that may run {string} and not {string}") { (slug: String, runs: String, not: String) =>
    kit.start()
    game.up()
    // Another suite's game service may hold either code in this JVM: an operator's removal first.
    Vector(runs, not).foreach(c => kit.testKit.componentClient.forKeyValueEntity(EntityId(c)).call(GameEntity.remove).invoke(): Unit)
    assertEquals(game.registerGame(runs, "pools").status, 200)
    assertEquals(game.registerGame(not, "scratch").status, 200)
    assertEquals(putEntry(slug, kit.brand.address, Seq(runs)).status, 200)
    fresh()
  }

  When("{string} asks for the list of games") { (slug: String) => answer = kit.games(slug) }

  Then("{string} is listed and {string} is not") { (in: String, out: String) =>
    assert(listed(answer).contains(in), answer.body)
    assert(!listed(answer).contains(out), answer.body)
  }

  Then("a launch of {string} from {string} is answered {string}") { (code: String, slug: String, word: String) =>
    assertEquals(kit.launch(ticket, player, code, site = slug).json.get("error").asText(), word)
  }

  // ---- a session belongs to one brand --------------------------------------------------------------

  Given("an open session of {string} and a brand entry for {string}") { (slug: String, other: String) =>
    kit.start()
    game.up()
    answer = null
    assertEquals(slug, "goldvibe")
    fresh()
    kit.open(ticket, player)
    assertEquals(putEntry(other, kit.spinvibe.address).status, 200)
    kit.brand.clear()
    kit.spinvibe.clear()
  }

  When("a check, a debit or a credit for that session arrives for {string}") { (slug: String) =>
    // The one way a session reaches another brand is that brand launching its ticket: the launch is
    // refused, and the session stays its brand's.
    kit.spinvibe.issue(ticket, player)
    answer = kit.launch(ticket, player, "test-slot", site = slug, issue = false)
  }

  Then("nothing is sent to either brand") { () =>
    assertEquals(kit.session(ticket).brand, "goldvibe")
    Thread.sleep(300)
    assertEquals(kit.brand.requests, Vector.empty)
    assertEquals(kit.spinvibe.requests, Vector.empty)
    // The game's next call goes to goldvibe, as before.
    assertEquals(game.stake(ticket, s"r-${System.nanoTime()}", 100).text("status"), Some("ok"))
    assertEquals(kit.spinvibe.requests, Vector.empty)
  }

  // ---- operators' reads -------------------------------------------------------------------------

  private var goldTickets = Set.empty[String]
  private var spinTickets = Set.empty[String]

  private def play(site: String, n: Int): Set[String] =
    (1 to n).map { i =>
      val t = s"L-$site-$i-${System.nanoTime()}"
      val brand = if site == "goldvibe" then kit.brand else kit.spinvibe
      brand.issue(t, s"p-$site-$i")
      assert(kit.launch(t, s"p-$site-$i", "test-slot", site = site, issue = false).json.has("game_url"))
      assertEquals(game.check(t).status, 200)
      assertEquals(game.stake(t, s"r-$t", 100).text("status"), Some("ok"))
      t
    }.toSet

  Given("rounds played at {string} and at {string}") { (a: String, b: String) =>
    kit.start()
    game.up()
    assertEquals(game.registerGame("test-slot", "slots").status, 200)
    assertEquals(putEntry(b, kit.spinvibe.address).status, 200)
    goldTickets = play(a, 3)
    spinTickets = play(b, 3)
  }

  private def sessionsOf(slug: String, token: String): Vector[String] =
    val a = kit.asStaff("GET", s"/v1/provider/brands/$slug/sessions", token)
    assertEquals(a.status, 200, a.body)
    a.json.get("sessions").elements().asScala.map(_.get("launchToken").asText()).toVector

  When("an operator reads the sessions and rounds of {string}") { (slug: String) =>
    kit.eventually()(Option(sessionsOf(slug, goldOperator)).filter(s => goldTickets.subsetOf(s.toSet))): Unit
    answers = Map(
      "sessions" -> kit.asStaff("GET", s"/v1/provider/brands/$slug/sessions", goldOperator),
      "rounds"   -> kit.asStaff("GET", s"/v1/provider/brands/$slug/rounds", goldOperator)
    )
  }

  Then("only the sessions and rounds of {string} are listed") { (slug: String) =>
    val sessions = answers("sessions").json.get("sessions").elements().asScala.map(_.get("launchToken").asText()).toSet
    val rounds   = answers("rounds").json.get("rounds").elements().asScala.map(_.get("launchToken").asText()).toSet
    assert(goldTickets.subsetOf(sessions))
    assertEquals(sessions.intersect(spinTickets), Set.empty)
    assert(goldTickets.subsetOf(rounds))
    assertEquals(rounds.intersect(spinTickets), Set.empty)
    val one = kit.asStaff("GET", s"/v1/provider/brands/$slug/rounds/${spinTickets.head}", goldOperator)
    assertEquals(one.status, 404, "another brand's session is not found under this brand")
  }

  // ---- who writes and reads -----------------------------------------------------------------------

  Given("an operator of the installation holding the {string} permission and an operator of {string} without it") { (role: String, slug: String) =>
    kit.start()
    assertEquals((role, slug), ("games", "goldvibe"))
    answer = null
  }

  When("each writes a brand entry and reads the sessions of {string} and of {string}") { (a: String, b: String) =>
    answers = Map(
      "installation-write" -> putEntry(b, kit.spinvibe.address, token = installation),
      "installation-read-a" -> kit.asStaff("GET", s"/v1/provider/brands/$a/sessions", installation),
      "installation-read-b" -> kit.asStaff("GET", s"/v1/provider/brands/$b/sessions", installation),
      "brand-write" -> putEntry(a, kit.brand.address, token = goldOperator),
      "brand-read-a" -> kit.asStaff("GET", s"/v1/provider/brands/$a/sessions", goldOperator),
      "brand-read-b" -> kit.asStaff("GET", s"/v1/provider/brands/$b/sessions", goldOperator),
      "player-read" -> kit.asStaff("GET", s"/v1/provider/brands/$a/sessions", ProviderTestKit.playerToken)
    )
  }

  Then("the installation's operator writes the entry and reads both brands") { () =>
    Vector("installation-write", "installation-read-a", "installation-read-b").foreach(k => assertEquals(answers(k).status, 200, s"$k: ${answers(k).body}"))
  }

  Then("the operator of {string} is refused the write and the read of {string}, and reads {string}") { (a: String, b: String, reads: String) =>
    assertEquals(answers("brand-write").status, 403)
    assert(answers("brand-write").body.contains("forbidden: the games permission is needed"), answers("brand-write").body)
    assertEquals(answers("brand-read-b").status, 403)
    assert(answers("brand-read-b").body.contains(s"brand_not_in_roles: no role names brand $b"), answers("brand-read-b").body)
    assertEquals(answers("brand-read-a").status, 200)
    assertEquals(answers("player-read").status, 403, "a token from a player realm is no operator")
  }
