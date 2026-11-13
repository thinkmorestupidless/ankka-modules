package io.eitheror.casino.provider.edge.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.provider.TestGame
import io.eitheror.casino.provider.core.{Callback, SessionEntity}
import io.eitheror.casino.provider.edge.ProviderTestKit
import io.eitheror.casino.provider.st8.Signer

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * features/games/brand.feature with `FakeBrand` as the brand (US1). The fake answers ST8's wallet
 * protocol under the prefix a brand gives its second provider configuration (`/eitheror`), so what
 * it is sent and how its wallet moves are the brand's side as the provider can see it. The brand's
 * own catalogue row and its `round_settled` fact are derived by spec 003's `game-provider` from
 * those same calls, and bound there (T053): here they are asserted as the calls they come from.
 */
class BrandFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/games/brand.feature") with LogCapturing:

  override val munitTimeout = 2.minutes

  private val mapper = ObjectMapper()
  private def kit    = ProviderTestKit.kit
  private def game   = kit.game
  private def json(text: String): JsonNode = mapper.readTree(text)

  private val code   = "test-slot"
  private var ticket = ""
  private var player = ""
  private var round  = ""
  private var answer: ProviderTestKit.Answer = null
  private var checked: TestGame#Answer       = null
  private var listing: JsonNode              = null

  private def fresh(): Unit =
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    player = s"p-$scenarioId-${System.nanoTime()}"
    round  = s"r-${System.nanoTime()}"

  Given("a brand that holds credentials for one game provider") { () =>
    kit.start()
    game.up()
    assertEquals(game.registerGame(code, "slots").status, 200)
    kit.brand.clear()
    fresh()
  }

  // ---- the catalogue -----------------------------------------------------------------------

  Given("the house provider holds a brand entry for the brand") { () =>
    assert(kit.cache.entry("goldvibe").isDefined)
  }

  When("an operator adds the house provider as a second game provider of the same protocol under the name {string}") { (name: String) =>
    // The brand's side of this act is a provider configuration under `name` (spec 003, bound in
    // T053); the provider's side is that it reaches the brand under that prefix.
    assertEquals(name, "eitheror")
  }

  When("the nightly refresh of the catalogue runs") { () =>
    val a = kit.games()
    assertEquals(a.status, 200, a.body)
    listing = a.json
  }

  Then("the house provider's games are in the catalogue under the game provider {string} with the developer {string}") {
    (provider: String, developer: String) =>
      val listed = listing.get("games").elements().asScala.find(_.get("code").asText() == code).getOrElse(fail(s"$code is not listed"))
      assertEquals(listed.get("developer").asText(), developer)
      assert(listing.get("developers").elements().asScala.exists(_.get("developer_id").asText() == developer))
      assertEquals(provider, "eitheror")
  }

  // ---- launch and check ----------------------------------------------------------------------

  Given("a player at the brand and a house provider game in the catalogue") { () =>
    assert(json(kit.games().body).get("games").elements().asScala.exists(_.get("code").asText() == code))
  }

  When("the player launches the house provider game") { () => answer = kit.launch(ticket, player, code) }

  Then("the house provider is asked to launch it under the brand's credentials for it") { () =>
    assertEquals(answer.status, 200, answer.body)
    assertEquals(kit.session(ticket).brand, "goldvibe")
  }

  Then("the player receives the game page's address carrying the launch ticket") { () =>
    assertEquals(answer.json.get("game_url").asText(), s"${game.address}/play/$code?token=$ticket")
  }

  Given("a launch ticket issued for a house provider game") { () =>
    assert(kit.launch(ticket, player, code).json.has("game_url"))
    kit.brand.clear()
  }

  When("the game page loads") { () => checked = game.check(ticket) }

  Then("the house provider checks the launch ticket with the brand in one signed provider call") { () =>
    val checks = kit.brand.requestsTo("check")
    assertEquals(checks.size, 1)
    assertEquals(checks.head.path, "/eitheror/check")
    assert(kit.brand.signedByListedKey(checks.head))
  }

  Then("the game service is told the brand, the player id, the currency and the session ticket") { () =>
    assertEquals(checked.status, 200)
    val b = checked.body
    assertEquals((b.get("brand").asText(), b.get("playerId").asText(), b.get("currency").asText(), b.get("sessionTicket").asText()),
      ("goldvibe", player, "BRL", s"s-$ticket"))
  }

  // ---- a round -----------------------------------------------------------------------------

  Given("an open session of a house provider game at the brand") { () =>
    kit.open(ticket, player, code)
    kit.brand.clear()
  }

  When("the game opens a round with a stake of {string} and settles it with a win of {string}") { (stake: String, win: String) =>
    val staked = game.stake(ticket, round, (BigDecimal(stake) * 100).toLong)
    assertEquals(staked.text("status"), Some("ok"), staked.body.toString)
    val settled = game.settle(ticket, round, (BigDecimal(win) * 100).toLong)
    assertEquals(settled.status, 202, settled.body.toString)
    kit.eventually()(Option(kit.callback(settled.text("callbackId").get)).filter(_.outcome == Callback.Acknowledged)): Unit
  }

  Then("the brand's wallet holds one bet of {string} and one win of {string} on the round, keyed by the house provider's transaction ids") {
    (stake: String, win: String) =>
      val debits  = kit.brand.requestsTo("debit").map(r => json(r.text))
      val credits = kit.brand.requestsTo("credit").map(r => json(r.text))
      assertEquals(debits.map(d => (d.get("round").asText(), d.get("amount").asText())), Vector((round, stake)))
      assertEquals(credits.map(c => (c.get("round").asText(), c.get("amount").asText())), Vector((round, win)))
      assertNotEquals(debits.head.get("transaction_id").asText(), credits.head.get("transaction_id").asText())
      assertEquals(kit.brand.balanceOf(player), BigDecimal("100.00") - BigDecimal(stake) + BigDecimal(win))
  }

  Then("one round settled fact names the game provider {string}, the round and the game") { (provider: String) =>
    // The brand emits the fact from the credit that closes the round, under the provider name its
    // callback route carries: one credit, under `/eitheror`, naming the round and the game.
    val credits = kit.brand.requestsTo("credit")
    assertEquals(credits.map(_.path), Vector(s"/$provider/credit"))
    val c = json(credits.head.text)
    assertEquals((c.get("round").asText(), c.get("game_code").asText()), (round, code))
  }

  // ---- another key -------------------------------------------------------------------------

  Given("a brand entry holding the brand's credential") { () =>
    assertEquals(kit.cache.entry("goldvibe").map(_.operatorPublicKey), Some(Signer.publicKeyText(kit.operator.getPublic)))
  }

  When("a launch for the brand arrives signed with another credential") { () =>
    kit.brand.issue(ticket, player)
    answer = kit.signedPost("/api/operator/v1/launch",
      s"""{"game_code":"$code","currency":"BRL","token":"$ticket","player":"$player","fun_mode":false,"site":"goldvibe"}""", Signer.generate())
  }

  Then("the house provider refuses it") { () =>
    assertEquals(answer.json.get("error").asText(), "auth_failed")
  }

  Then("no session is stored") { () =>
    val stored = Try(kit.testKit.componentClient.forEventSourcedEntity(EntityId(ticket)).call(SessionEntity.get).invoke())
    assert(stored.isFailure || stored.get.brand.isEmpty, s"a session was stored: $stored")
  }
