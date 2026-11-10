package io.eitheror.casino.provider.edge.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.provider.core.Callback
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.provider.edge.ProviderTestKit
import io.eitheror.casino.provider.edge.registry.GameEntity

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/games/free-rounds.feature: the brand's bonus calls, the test game's instances (US5). */
class FreeRoundsFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/games/free-rounds.feature") with LogCapturing:

  override val munitTimeout = 2.minutes

  private val mapper = ObjectMapper()
  private def kit    = ProviderTestKit.kit
  private def game   = kit.game
  private def json(text: String): JsonNode = mapper.readTree(text)

  private var bonusId = ""
  private var player  = ""
  private var ticket  = ""
  private var request = ""
  private var answer: ProviderTestKit.Answer = null
  private var asked   = 0

  override def afterEach(context: AfterEach): Unit = kit.offset = 0L

  private def fresh(): Unit =
    bonusId = s"bonus-$scenarioId-${System.nanoTime()}"
    player  = s"p-$scenarioId-${System.nanoTime()}"
    ticket  = s"L-$scenarioId-${System.nanoTime()}"

  private def createBody(code: String, count: Int, durationSeconds: Long = 0): String =
    s"""{"bonus_id":"$bonusId","players":["$player"],"count":$count,"value":"0.10","type":"free_bets","currency":"BRL",""" +
      s""""game_codes":["$code"],"site":"goldvibe"${if durationSeconds > 0 then s""","duration":$durationSeconds""" else ""}}"""

  private def bonusCalls = game.requests.count(_.path.startsWith("/v1/game/bonus"))

  private def instance(a: ProviderTestKit.Answer): JsonNode =
    a.json.get("bonus").get("instances").elements().asScala.find(_.get("player").asText() == player).getOrElse(fail(a.body))

  Given("a house provider serving a brand, with a game service that registered {string}") { (code: String) =>
    kit.start()
    game.up()
    assertEquals(game.registerGame(code, "pools").status, 200)
    fresh()
  }

  Given("a request from the brand to create free rounds on {string} for a player, count {string}") { (code: String, count: String) =>
    request = createBody(code, count.toInt)
  }

  Given("a request from the brand to create free rounds on {string}, which no game service registered") { (code: String) =>
    // Another suite may have registered the code in this JVM: an operator's removal first, which
    // answers not found when none did (the suite run alone).
    scala.util.Try(kit.testKit.componentClient.forKeyValueEntity(EntityId(code)).call(GameEntity.remove).invoke()): Unit
    request = createBody(code, 1)
  }

  When("the house provider receives it") { () =>
    asked = bonusCalls
    answer = kit.signedPost("/api/operator/v1/bonus/create", request)
  }

  Then("the game service holds one free rounds instance for the player with count {string}") { (count: String) =>
    val held = game.bonus(bonusId).getOrElse(fail(s"the game holds no bonus $bonusId"))
    assertEquals(held.instances.keySet, Set(player))
    assertEquals(held.instances(player).remaining, count.toInt)
    assertEquals(held.site, "goldvibe") // the test game keeps the brand it was asked for in `site`
  }

  Then("the brand is answered {string} with that count") { (status: String) =>
    assertEquals(answer.json.get("status").asText(), "ok", answer.body)
    val i = instance(answer)
    assertEquals((i.get("status").asText(), i.get("remaining").asInt()), (status, 1))
  }

  // The stand-in and ST8 spell a cancelled instance `canceled`, which the brand's adapter reads as cancelled.
  Then("the brand is answered {string}") { (word: String) =>
    val got = Option(answer.json.get("error")).getOrElse(instance(answer).get("status")).asText()
    assertEquals(if got == "canceled" then "cancelled" else got, word, answer.body)
  }

  Then("no game service is asked") { () => assertEquals(bonusCalls, asked) }

  // ---- cancel ---------------------------------------------------------------------------------------

  Given("free rounds the brand created on {string}") { (code: String) =>
    val created = kit.signedPost("/api/operator/v1/bonus/create", createBody(code, 2))
    assertEquals(created.json.get("status").asText(), "ok", created.body)
  }

  When("the brand cancels them") { () =>
    answer = kit.signedPost("/api/operator/v1/bonus/cancel", s"""{"bonus_id":"$bonusId","player":"$player","site":"goldvibe"}""")
  }

  Then("the game service's instance is cancelled") { () =>
    assertEquals(game.bonus(bonusId).map(_.instances(player).status), Some("canceled"))
    // And the brand reads it so, by the index, signed over its query.
    val read = kit.signedGet("/api/operator/v1/bonus/get", s"bonus_id=$bonusId&site=goldvibe")
    assertEquals(instance(read).get("status").asText(), "canceled")
  }

  // ---- a free round ---------------------------------------------------------------------------------

  Given("a player with an active free rounds instance on {string} and a launch ticket carrying the free rounds") { (code: String) =>
    assertEquals(kit.signedPost("/api/operator/v1/bonus/create", createBody(code, 2)).json.get("status").asText(), "ok")
    kit.open(ticket, player, code)
    kit.brand.clear()
  }

  private var round = ""

  When("the game service spends one free round for the player") { () =>
    round = s"r-free-${System.nanoTime()}"
    val (staked, settled) = game.freeRound(ticket, round, bonusId, player, 40L).getOrElse(fail("no active instance"))
    assertEquals(staked.text("status"), Some("ok"), staked.body.toString)
    assertEquals(settled.status, 202, settled.body.toString)
    kit.eventually()(Option(kit.callback(settled.text("callbackId").get)).filter(_.outcome == Callback.Acknowledged)): Unit
  }

  Then("the house provider sends a debit of {string} of kind {string} and then a credit on the same round") { (amount: String, kind: String) =>
    val debits  = kit.brand.requestsTo("debit").map(r => json(r.text))
    val credits = kit.brand.requestsTo("credit").map(r => json(r.text))
    assertEquals(debits.map(d => (d.get("amount").asText(), d.get("provider_kind").asText(), d.get("round").asText())), Vector((amount, kind, round)))
    assertEquals(credits.map(c => (c.get("amount").asText(), c.get("round").asText())), Vector(("0.40", round)))
    assert(kit.brand.requests.indexWhere(_.path.endsWith("/debit")) < kit.brand.requests.indexWhere(_.path.endsWith("/credit")))
    assert(kit.session(ticket).round(round).exists(_.free))
  }

  Then("the instance's count is decremented") { () =>
    assertEquals(game.bonus(bonusId).map(_.instances(player).remaining), Some(1))
    val read = kit.signedGet("/api/operator/v1/bonus/get", s"bonus_id=$bonusId&site=goldvibe")
    assertEquals(instance(read).get("remaining").asInt(), 1)
  }

  // ---- the claim deadline -----------------------------------------------------------------------------

  private var askedUntil: Instant = null

  Given("free rounds created with a deadline the brand asked, on a game whose rounds settle {string} days later") { (days: String) =>
    // The brand asks three days; the test game's rounds run a week (its own schedule).
    assertEquals(days, "7")
    askedUntil = Instant.now().plus(JDuration.ofDays(3))
    request = createBody("loteca", 1, durationSeconds = 3L * 86400)
  }

  When("the game service sets the instance's claim deadline") { () =>
    answer = kit.signedPost("/api/operator/v1/bonus/create", request)
    assertEquals(answer.json.get("status").asText(), "ok", answer.body)
  }

  Then("the claim deadline is no earlier than the brand asked") { () =>
    val deadline = game.claimDeadline(bonusId).getOrElse(fail("no claim deadline"))
    assert(!deadline.isBefore(askedUntil.minusSeconds(5)), s"$deadline is before $askedUntil")
    assert(!deadline.isBefore(Instant.now().plus(JDuration.ofDays(7)).minusSeconds(5)), "the game's own week")
    val ask = game.requests.filter(_.path == "/v1/game/bonus").map(r => json(r.text)).find(_.get("bonusId").asText() == bonusId).get
    assertEquals(ask.get("durationDays").asInt(), 3)
  }

  Then("a free round spent before the claim deadline is sent however late its round settles") { () =>
    kit.open(ticket, player, "loteca")
    round = s"r-late-${System.nanoTime()}"
    assert(game.spendFreeRound(bonusId, player).isDefined)
    assertEquals(game.stake(ticket, round, 0L, "free_debit").text("status"), Some("ok"))
    kit.brand.clear()
    kit.offset += 10.days.toMillis // past the claim deadline before the round settles
    val settled = game.settle(ticket, round, 25L)
    assertEquals(settled.status, 202, settled.body.toString)
    kit.eventually()(Option(kit.callback(settled.text("callbackId").get)).filter(_.outcome == Callback.Acknowledged)): Unit
    assertEquals(kit.brand.requestsTo("credit").map(r => json(r.text).get("round").asText()), Vector(round))
  }
