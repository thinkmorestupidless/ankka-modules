package io.eitheror.casino.provider.edge.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.provider.TestGame
import io.eitheror.casino.provider.core.{Callback, Round}
import io.eitheror.casino.provider.edge.ProviderTestKit
import io.eitheror.casino.provider.edge.registry.GameEntity

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/games/contract.feature against the provider, `TestGame` and `FakeBrand` (US2). */
class ContractFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/games/contract.feature") with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = ProviderTestKit.kit
  private def game   = kit.game
  private def json(text: String): JsonNode = mapper.readTree(text)

  private var code     = ""
  private var category = ""
  private var ticket   = ""
  private var player   = ""
  private var round    = ""
  private var debitTx  = ""
  private var callback = ""
  private var answer: ProviderTestKit.Answer = null
  private var played: TestGame#Answer = null
  private var other: TestGame = null

  private def fresh(): Unit =
    ticket = s"L-$scenarioId-${System.nanoTime()}"
    player = s"p-$scenarioId"
    round  = s"r-${System.nanoTime()}"

  private def registered(c: String, demo: Boolean = true): Unit =
    code = c
    val a = game.registerGame(c, if category.isEmpty then "slots" else category, demo)
    assertEquals(a.status, 200, a.body.toString)

  private def opened(c: String, funMode: Boolean = false): Unit =
    registered(c)
    fresh()
    assertEquals(kit.launch(ticket, player, c, funMode).status, 200)
    assertEquals(game.check(ticket).status, 200)
    kit.brand.clear()

  private def bodies(kind: String): Vector[JsonNode] = kit.brand.requestsTo(kind).map(r => json(r.text))

  Given("a house provider serving a brand") { () =>
    kit.start()
    game.up()
    category = ""
    kit.brand.clear()
  }

  // ---- registration and the games list --------------------------------------------------------

  Given("a game service with a game coded {string} in the category {string}") { (c: String, cat: String) =>
    code = c
    category = cat
  }

  When("the game service registers its games with the house provider") { () => registered(code) }

  Then("the brand's request for the list of games answers {string} with the developer {string} and the category {string}") {
    (c: String, developer: String, cat: String) =>
      val listed = json(kit.games().body).get("games").elements().asScala.find(_.get("code").asText() == c)
        .getOrElse(fail(s"$c is not listed"))
      assertEquals((listed.get("developer").asText(), listed.get("category").asText()), (developer, cat))
  }

  // ---- launch ---------------------------------------------------------------------------------

  Given("a game service that registered the code {string}") { (c: String) => registered(c) }

  When("the brand asks the house provider to launch {string}") { (c: String) =>
    fresh()
    answer = kit.launch(ticket, player, c)
  }

  Then("the answer is the game service's game page address carrying the launch ticket") { () =>
    assertEquals(answer.status, 200)
    assertEquals(answer.json.get("game_url").asText(), s"${game.address}/play/$code?token=$ticket")
  }

  Then("a launch of a code no game service registered is answered {string}") { (word: String) =>
    val a = kit.launch(s"L-none-${System.nanoTime()}", player, "no-such-game")
    assertEquals(a.json.get("error").asText(), word)
  }

  // ---- check ----------------------------------------------------------------------------------

  Given("a launch ticket the brand issued for {string}") { (c: String) =>
    registered(c)
    fresh()
    assertEquals(kit.launch(ticket, player, c).status, 200)
    kit.brand.clear()
  }

  When("the game service asks the house provider to check it") { () => played = game.check(ticket) }

  Then("the house provider sends one signed check to the brand") { () =>
    val checks = kit.brand.requestsTo("check")
    assertEquals(checks.size, 1)
    assert(checks.head.path.startsWith("/eitheror/"), checks.head.path)
    assert(kit.brand.signedByListedKey(checks.head))
    assertEquals(checks.head.text, s"""{"token":"$ticket"}""")
  }

  Then("the game service is answered the brand, the player id, the currency, the language and whether the session is in fun mode") { () =>
    assertEquals(played.status, 200)
    val b = played.body
    assertEquals(
      (b.get("brand").asText(), b.get("playerId").asText(), b.get("currency").asText(), b.get("language").asText(), b.get("demo").asBoolean()),
      ("goldvibe", player, "BRL", "pt", false)
    )
    assertEquals(b.get("sessionTicket").asText(), s"s-$ticket")
  }

  Then("nothing of the launch's player profile is kept or answered") { () =>
    assert(!played.body.toString.contains("player@example.com"), played.body.toString)
    assert(!kit.session(ticket).toString.contains("player@example.com"))
    assert(!kit.session(ticket).toString.contains("Ana"))
  }

  Then("a launch ticket the brand answers {string} is answered to the game service as refused, and the game page shows the session expired page") {
    (word: String) =>
      // The ticket is one the brand never issued, which it answers `session_expired`. The page the
      // player then sees is the game service's to render (spec 027); TestGame serves none.
      val unissued = s"L-unissued-${System.nanoTime()}"
      assertEquals(kit.launch(unissued, player, code, issue = false).status, 200)
      val refused = game.check(unissued)
      assertEquals(refused.text("refused"), Some(word))
      assert(kit.session(unissued).refused)
  }

  // ---- debits -------------------------------------------------------------------------------

  Given("an open session of {string}") { (c: String) => opened(c) }

  When("the game service asks for a debit of {string} minor units on a round it names") { (amount: String) =>
    played = game.stake(ticket, round, amount.toLong)
  }

  Then("the house provider sends one signed debit of {string} under a new transaction id") { (amount: String) =>
    val debits = kit.brand.requestsTo("debit")
    assertEquals(debits.size, 1)
    assert(kit.brand.signedByListedKey(debits.head))
    val b = json(debits.head.text)
    assertEquals((b.get("amount").asText(), b.get("round").asText(), b.get("currency").asText()), (amount, round, "BRL"))
    assert(b.get("transaction_id").asText().matches("[0-9a-f-]{36}"))
    assertEquals(played.text("callbackId"), Some(b.get("transaction_id").asText()))
  }

  Then("the brand's answer word and balance are answered to the game service") { () =>
    assertEquals(played.status, 200)
    assertEquals(played.text("status"), Some("ok"))
    assertEquals(played.body.get("balance").asLong(), kit.brand.balanceOf(player).*(100).toLong)
    assertEquals(kit.session(ticket).round(round).map(_.state), Some(Round.Open))
  }

  Given("an open session of {string} at a brand that does not answer") { (c: String) =>
    opened(c)
    // Answered after the wait (1 s): processed, then late, so the retry is answered as it was.
    kit.brand.slow("debit", 1, 2500)
  }

  When("the game service asks for a debit") { () => played = game.stake(ticket, round, 100) }

  Then("the game service is answered a pending debit") { () =>
    assertEquals(played.status, 202)
    assertEquals(played.text("status"), Some("pending"))
    callback = played.text("callbackId").get
    assertEquals(kit.session(ticket).round(round).map(_.state), Some(Round.Pending))
    assertEquals(game.settle(ticket, round, 0).status, 409)
    // The refused settle's callback was scheduled first and is discarded (review, second round): the
    // run holds the check and the debit, and no credit, once the view has had time to see both events.
    val run = kit.session(ticket).runId
    def rows = io.eitheror.casino.provider.core.Sweep.views.get.forView(io.eitheror.casino.provider.core.RunCallbacks)
      .ask(io.eitheror.casino.provider.core.RunCallbacks.byRun, 100, "run" -> run, "after_at" -> "-1", "after_id" -> "")
    kit.eventually()(Option(rows).filter(_.exists(_.callbackId == callback))): Unit
    Thread.sleep(1000)
    assertEquals(rows.filter(_.kind == "credit"), Vector.empty)
  }

  Then("the house provider retries the debit under the same transaction id on the retry schedule") { () =>
    val debits = kit.eventually()(Option(bodies("debit")).filter(_.size >= 2))
    assertEquals(debits.map(_.get("transaction_id").asText()).distinct, Vector(callback))
    assertEquals(debits.map(_.toString).distinct.size, 1)
  }

  Then("the game service reads the brand's answer once the brand answers") { () =>
    val read = kit.eventually()(Option(game.callback(callback)).filter(_.text("state").contains(Callback.Acknowledged)))
    assertEquals(read.body.get("answer").get("status").asText(), "ok")
    assertEquals(kit.eventually()(kit.session(ticket).round(round).map(_.state).filter(_ == Round.Open)), Round.Open)
  }

  // ---- credits and cancels --------------------------------------------------------------------

  Given("a round of {string} opened by a debit the brand answered {string}") { (c: String, word: String) =>
    opened(c)
    val staked = game.stake(ticket, round, 150)
    assertEquals(staked.text("status"), Some(word))
    debitTx = staked.text("callbackId").get
    kit.brand.clear()
  }

  When("the game service asks for a credit of {string} minor units on the round") { (amount: String) =>
    played = game.settle(ticket, round, amount.toLong)
  }

  Then("the house provider accepts it at once and sends one signed credit of {string}") { (amount: String) =>
    assertEquals(played.status, 202)
    assertEquals(played.text("status"), Some("accepted"))
    callback = played.text("callbackId").get
    val credit = kit.eventually()(bodies("credit").headOption)
    assertEquals((credit.get("amount").asText(), credit.get("round").asText()), (amount, round))
    assert(kit.brand.signedByListedKey(kit.brand.requestsTo("credit").head))
  }

  Then("a credit the brand answers {string} or does not answer is retried on the retry schedule") { (word: String) =>
    assertEquals(word, "error")
    // `error` once, then late once; each is sent again under the same transaction id.
    val again = s"r-again-${System.nanoTime()}"
    assertEquals(game.stake(ticket, again, 100).text("status"), Some("ok"))
    kit.brand.clear()
    kit.brand.failing("credit", 1)
    val erred = game.settle(ticket, again, 200).text("callbackId").get
    val sent  = kit.eventually()(Option(bodies("credit")).filter(_.size >= 2))
    assertEquals(sent.map(_.get("transaction_id").asText()).distinct, Vector(erred))
    kit.eventually()(Option(kit.callback(erred)).filter(_.outcome == Callback.Acknowledged))
    val late = s"r-late-${System.nanoTime()}"
    assertEquals(game.stake(ticket, late, 100).text("status"), Some("ok"))
    kit.brand.clear()
    kit.brand.slow("credit", 1, 2500)
    val slow = game.settle(ticket, late, 200).text("callbackId").get
    kit.eventually()(Option(bodies("credit")).filter(_.size >= 2))
    kit.eventually()(Option(kit.callback(slow)).filter(_.outcome == Callback.Acknowledged))
  }

  Then("the game service reads whether the brand acknowledged it") { () =>
    val read = kit.eventually()(Option(game.callback(callback)).filter(_.text("state").contains(Callback.Acknowledged)))
    assert(read.body.get("attempts").asInt() >= 1)
  }

  When("the game service asks for a credit of {string} on the round") { (amount: String) =>
    played = game.settle(ticket, round, amount.toLong)
  }

  Then("the house provider sends a credit of {string} on the round") { (amount: String) =>
    val credit = kit.eventually()(bodies("credit").headOption)
    assertEquals((credit.get("amount").asText(), credit.get("round").asText()), (amount, round))
    assertEquals(kit.session(ticket).round(round).map(_.state), Some(Round.Lost))
  }

  When("the game service asks to cancel the debit") { () => played = game.cancel(ticket, round) }

  Then("the house provider sends a cancel under a new cancel id naming the debit's transaction id") { () =>
    assertEquals(played.status, 202)
    val cancel = kit.eventually()(bodies("cancel").headOption)
    assertEquals(cancel.get("transaction_id").asText(), debitTx)
    assertEquals(Some(cancel.get("cancel_id").asText()), played.text("cancelId"))
    assertNotEquals(cancel.get("cancel_id").asText(), debitTx)
  }

  Then("the round is closed as cancelled when the brand acknowledges the cancel") { () =>
    val id = played.text("callbackId").get
    kit.eventually()(Option(kit.callback(id)).filter(_.outcome == Callback.Acknowledged))
    assertEquals(kit.session(ticket).round(round).map(_.state), Some(Round.Cancelled))
  }

  // ---- a cancel of a pending debit (review, should fix 1) -------------------------------------------

  Given("a debit asked by the game service that is answered pending") { () =>
    // Every debit late (past the 1 s wait), so the stake stays pending and its delivery keeps retrying.
    kit.brand.slow("debit", 10, 2500)
    val staked = game.stake(ticket, round, 100)
    assertEquals(staked.text("status"), Some("pending"), staked.body.toString)
    debitTx = staked.text("callbackId").get
  }

  Then("the debit is retried no more and the cancel is sent naming its transaction id") { () =>
    // The brand answers promptly again (the delivery was stopped by the cancel), and the scenarios after this one expect it.
    kit.brand.slow("debit", 0, 0)
    assertEquals(played.status, 202, played.body.toString)
    val cancel = kit.eventually()(bodies("cancel").headOption)
    assertEquals(cancel.get("transaction_id").asText(), debitTx)
    assertEquals(kit.eventually()(Option(kit.callback(debitTx)).filter(_.outcome == Callback.Abandoned)).outcome, Callback.Abandoned)
    val debits = kit.brand.requestsTo("debit").size
    Thread.sleep(3000)
    assertEquals(kit.brand.requestsTo("debit").size, debits, "a debit was sent after the cancel")
  }

  // ---- demo, another game's session -------------------------------------------------------------

  Given("an open session of {string} in fun mode") { (c: String) => opened(c, funMode = true) }

  When("the game service asks for a debit on a round") { () => played = game.stake(ticket, round, 100) }

  Then("the house provider sends nothing") { () =>
    Thread.sleep(300)
    assertEquals(kit.brand.requests, Vector.empty)
  }

  Then("the game service is answered a demo round") { () =>
    assertEquals(played.status, 200)
    assertEquals(played.text("status"), Some("demo"))
  }

  Given("an open session of {string} and a game service that registered only {string}") { (c: String, theirs: String) =>
    opened(c)
    if other == null then
      other = TestGame(kit.base)
      other.name = "other-game"
      other.identity = Seq(kit.testKit.asCaller(Caller.Service("games", "other-game")))
    assertEquals(other.registerGame(theirs, "slots").status, 200)
  }

  When("the second game service asks for a debit on that session") { () => played = other.stake(ticket, round, 100) }

  Then("the house provider refuses it") { () => assertEquals(played.status, 403) }

  Then("nothing is sent") { () =>
    Thread.sleep(300)
    assertEquals(kit.brand.requestsTo("debit"), Vector.empty)
  }

  // ---- a game deregistered with a round open (review, blocking 1) ------------------------------------

  private def otherGame(): TestGame =
    if other == null then
      other = TestGame(kit.base)
      other.name = "other-game"
      other.identity = Seq(kit.testKit.asCaller(Caller.Service("games", "other-game")))
    other

  Given("{string} is then deregistered by its game service and registered by another game service") { (c: String) =>
    assertEquals(game.deregister(c).status, 200)
    assertEquals(otherGame().registerGame(c, "slots").status, 200)
  }

  Then("the other game service is refused on that session") { () =>
    val statuses = (other.settle(ticket, round, 100).status, other.cancel(ticket, round).status)
    // The code back with its first owner before asserting, so a failure here leaves the later scenarios alone.
    assertEquals(other.deregister(code).status, 200)
    registered(code)
    assertEquals(statuses, (403, 403))
  }

  // ---- a game service down; a game without demo -----------------------------------------------------

  Given("a game service that registered {string} and is then down") { (c: String) =>
    registered(c)
    game.down()
  }

  When("the brand asks for the list of games") { () => answer = kit.games() }

  private def listed(a: ProviderTestKit.Answer): Vector[String] = json(a.body).get("games").elements().asScala.map(_.get("code").asText()).toVector

  Then("{string} is still listed") { (c: String) => assert(listed(answer).contains(c), answer.body) }

  Then("a launch of {string} is answered as unavailable until the game service is back") { (c: String) =>
    fresh()
    assertEquals(kit.launch(ticket, player, c).json.get("error").asText(), "unavailable")
    game.up()
    fresh()
    assert(kit.launch(ticket, player, c).json.has("game_url"))
  }

  Then("{string} leaves the list only when the game service deregisters it or an operator removes it") { (c: String) =>
    assert(listed(kit.games()).contains(c))
    assertEquals(game.deregister(c).status, 200)
    assert(!listed(kit.games()).contains(c))
    // An operator's removal (the route is US6's): the entity's own command, whoever owns the game.
    registered(c)
    kit.testKit.componentClient.forKeyValueEntity(EntityId(c)).call(GameEntity.remove).invoke(): Unit
    assertEquals(kit.launch(s"L-gone-${System.nanoTime()}", player, c).json.get("error").asText(), "unknown_game")
  }

  Given("a game service that registered {string} without demo play") { (c: String) => registered(c, demo = false) }

  When("the brand asks the house provider to launch {string} in fun mode") { (c: String) =>
    fresh()
    answer = kit.launch(ticket, player, c, funMode = true)
  }

  Then("the answer is {string}") { (word: String) => assertEquals(answer.json.get("error").asText(), word) }

  Then("the list of games says {string} does not support demo play") { (c: String) =>
    val g = json(kit.games().body).get("games").elements().asScala.find(_.get("code").asText() == c).get
    assertEquals(g.get("fun_mode").asBoolean(), false)
  }
