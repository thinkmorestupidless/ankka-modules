package io.eitheror.casino.gameprovider.features

import io.eitheror.casino.gameprovider.Json
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.Reply
import io.eitheror.casino.gameprovider.rounds.Cleared

/** features/game-provider/unacknowledged.feature (US4, FR-020). */
class UnacknowledgedFeatures extends ProviderFeatures("unacknowledged.feature"):

  sessionTicketSteps()

  private var replayed: Reply = null
  private var moneyCalls      = 0

  private def backoffice(method: String, path: String, body: Option[String] = None): Reply =
    kit.request(method, s"/v1/casino/backoffice/${kit.brand}/game-provider$path", body, kit.asOperator("op-7"))

  private def failedCredit(tx: String, amount: String, round: String): Unit =
    assertEquals(debit(s"$tx-stake", "1.00", round).word, "ok")
    kit.wallet.down = true
    assertEquals(credit(tx, amount, Some(round)).word, "error")
    kit.wallet.down = false
    kit.wallet.clearReceived()

  private def listed(tx: String) =
    kit.eventually(s"${id(tx)} listed") {
      backoffice("GET", "/unacknowledged").json.objects("entries").find(_.text("transactionId").contains(id(tx)))
    }

  Given("a credit {string} of {string} in round {string} that failed to reach the wallet and was answered {string}") {
    (tx: String, amount: String, round: String, word: String) =>
      assertEquals(word, "error")
      failedCredit(tx, amount, round)
  }

  When("an operator lists the brand's unacknowledged calls") { () => listed("t3"): Unit }

  Then("{string} is listed with its kind, amount, round and error") { (tx: String) =>
    val e = listed(tx)
    assertEquals(e.text("kind"), Some("credit"))
    assertEquals(e.long("amount"), Some(1200L))
    assertEquals(e.text("roundId"), Some(id("r3")))
    assertEquals(e.text("playerId"), Some(player))
    assert(e.text("error").exists(_.nonEmpty))
  }

  When("an operator replays {string}") { (tx: String) =>
    listed(tx)
    replayed = backoffice("POST", s"/unacknowledged/${id(tx)}/replay")
    assertEquals(replayed.status, 200, replayed.text)
  }

  Then("the wallet is credited {string} keyed {string}") { (amount: String, tx: String) =>
    val win = kit.wallet.calls("wins").flatMap(_.json).head
    assertEquals(win.long("amount"), Some(minor(amount)))
    assert(win.text("key").exists(_.startsWith(id(tx))), win.toString)
    assertEquals(replayed.word, "ok")
  }

  Then("the unacknowledged call is cleared") { () =>
    assert(kit.round(id("r3")).openUnacknowledged(id("t3")).isEmpty)
    kit.eventually("the listing no longer shows it")(
      Option.when(!backoffice("GET", "/unacknowledged").json.objects("entries").exists(_.text("transactionId").contains(id("t3"))))(()))
  }

  Then("round {string} records the replay with the operator and the time") { (round: String) =>
    val c = kit.round(id(round)).unacknowledged.find(_.transactionId == id("t3")).flatMap(_.cleared).get
    assertEquals((c.how, c.operator), (Cleared.OperatorReplay, Some("op-7")))
    assertEquals(c.at, kit.clock)
  }

  Given("an unacknowledged credit {string} an operator replayed") { (tx: String) =>
    failedCredit(tx, "12.00", "r3")
    listed(tx)
    replayed = backoffice("POST", s"/unacknowledged/${id(tx)}/replay")
    assertEquals(replayed.word, "ok", replayed.text)
  }

  When("{string} arrives again") { (tx: String) =>
    moneyCalls = kit.wallet.moneyCalls.size
    credit(tx, "12.00", Some("r3"))
  }

  Then("it is answered with the replay's outcome and balance") { () =>
    assertEquals(last.word, "ok")
    assertEquals(last.json.text("balance"), replayed.json.text("balance"))
  }

  Then("the wallet is not called") { () => assertEquals(kit.wallet.moneyCalls.size, moneyCalls) }

  When("an operator resolves {string} with a reason") { (tx: String) =>
    listed(tx)
    val r = backoffice("POST", s"/unacknowledged/${id(tx)}/resolve", Some(Json.render(Json.obj("reason" -> "paid by bank transfer"))))
    assertEquals(r.status, 204, r.text)
  }

  Then("no money moves") { () => assertEquals(kit.wallet.moneyCalls, Vector.empty) }

  Then("round {string} records the reason, the operator and the time") { (round: String) =>
    val c = kit.round(id(round)).unacknowledged.find(_.transactionId == id("t3")).flatMap(_.cleared).get
    assertEquals((c.how, c.reason, c.operator, c.at), (Cleared.OperatorResolve, Some("paid by bank transfer"), Some("op-7"), kit.clock))
  }
