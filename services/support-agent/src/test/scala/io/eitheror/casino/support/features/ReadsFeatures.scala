package io.eitheror.casino.support.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json as AgentJson}
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.agent.Context
import io.eitheror.casino.support.config.AgentConfiguration
import io.eitheror.casino.support.it.Answer

/**
 * features/support-agent/reads.feature (US1; T029). The model is scripted: it calls the read the question needs and
 * then answers. What is asserted is what the service did with it: what the tool gave the model, which neighbour it
 * read and how, and what the stream carried. The model's own words are not the service's, so they are not asserted;
 * "in major units" is the prompt's instruction, which the default prompt carries.
 */
class ReadsFeatures extends SupportFeatures("reads.feature"):

  /** The model calls `tool`, then answers `reply`; the player asks `message`. */
  private def ask(message: String, tool: Option[String], reply: String = "Here you are."): Unit =
    tool.foreach(t => kit.model.expectToolCall(t, AgentJson.obj(), s"call-$t"))
    kit.model.expectText(reply)
    stream = kit.chat(player, session, message)

  /** The model's requests, once the turn's stream has ended. */
  private def requests = { stream.frames(): Unit; kit.model.requests }

  /** What the read `tool` gave the model, as JSON. */
  private def result(tool: String): JsonNode =
    val content = requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten
      .find(_.name == tool).map(_.content).getOrElse(fail(s"the model was given no result of $tool"))
    Json.parse(content).getOrElse(fail(s"$tool's result is not JSON: $content"))

  private def resultText(tool: String): String =
    requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten
      .find(_.name == tool).map(_.content).getOrElse(fail(s"the model was given no result of $tool"))

  private def sent: String = requests.map(_.toString).mkString("\n")

  private def calledWithoutToken(fake: io.eitheror.casino.support.it.FakeNeighbour, path: String): Unit =
    val calls = fake.calls(path)
    assert(calls.nonEmpty, s"${fake.name} was not asked $path")
    assertEquals(calls.map(fake.bearer).distinct, Vector(None), "a read is made by the service's identity, not the player's token")

  // ── a balance ─────────────────────────────────────────────────────────────

  Given("the player has a wallet in {string}") { (currency: String) =>
    kit.wallet.answer("GET", s"/wallets/$player/$currency/balance",
      Answer.ok(Json.obj("realBalance" -> 5000L, "restrictedBalance" -> 250L, "currency" -> currency)))
  }
  When("they ask the support agent for their balance") { () => ask("what is my balance?", Some("get_wallet_balance")) }
  Then("the reply states the real balance and the restricted balance in major units of {string}") { (currency: String) =>
    val b = result("get_wallet_balance")
    assertEquals((b.long("real_balance"), b.long("restricted_balance"), b.text("currency")), (Some(5000L), Some(250L), Some(currency)))
    assert(sent.contains("Convert to major units"), "the prompt tells the model to answer in major units")
    assertEquals(stream.names().last, "done")
    assertEquals(stream.text(), "Here you are.")
  }
  Then("it was read from the wallet by the support agent's own identity") { () =>
    calledWithoutToken(kit.wallet, s"/wallets/$player/GBP/balance")
  }

  // ── rewards ───────────────────────────────────────────────────────────────

  Given("the player has two active rewards") { () =>
    def grant(id: String, required: Long, wagered: Long, expires: String) = Json.obj("rewardId" -> id, "offerName" -> s"Offer $id",
      "wageringRequirement" -> required, "wageredAmount" -> wagered, "expiresAt" -> expires, "status" -> "active")
    kit.rewards.answer("GET", s"/rewards/$player/grants", Answer.ok(Json.obj("items" -> Json.arr(
      grant("g-1", 3000L, 1000L, "2026-12-01T00:00:00Z"), grant("g-2", 5000L, 0L, "2026-12-15T00:00:00Z"),
      Json.obj("rewardId" -> "g-3", "status" -> "expired")))))
  }
  When("they ask the support agent about their bonuses") { () => ask("what bonuses do I have?", Some("get_active_bonuses")) }
  Then("the reply names each reward with its remaining wagering requirement and its expiry, read from the brand's rewards") { () =>
    val bonuses = result("get_active_bonuses").objects("bonuses")
    assertEquals(bonuses.map(b => (b.text("name"), b.long("wagering_requirement"), b.long("wagering_progress"), b.text("expires_at"))),
      Vector((Some("Offer g-1"), Some(3000L), Some(1000L), Some("2026-12-01T00:00:00Z")),
        (Some("Offer g-2"), Some(5000L), Some(0L), Some("2026-12-15T00:00:00Z"))))
    calledWithoutToken(kit.rewards, s"/rewards/$player/grants")
  }

  // ── transactions ──────────────────────────────────────────────────────────

  Given("the player has recent transactions") { () =>
    val txs = (1 to 10).map(i => Json.obj("transactionId" -> s"t-$i", "type" -> "DEPOSIT", "amount" -> (1000L * i), "currency" -> "GBP",
      "status" -> "COMPLETED", "createdAt" -> s"2026-11-0${(i % 9) + 1}T10:00:00Z", "paymentReference" -> s"ref-$i", "reason" -> "internal"))
    kit.wallet.answer("GET", s"/wallets/$player/GBP/transactions", Answer.ok(Json.obj("transactions" -> Json.arr(txs*))))
  }
  When("they ask the support agent for recent activity") { () => ask("what did I do recently?", Some("get_transaction_history")) }
  Then("the reply lists the last ten with amount, kind and date, read from the wallet") { () =>
    val txs = result("get_transaction_history").objects("transactions")
    assertEquals(txs.size, 10)
    assert(txs.forall(t => t.long("amount").isDefined && t.text("type").isDefined && t.text("created_at").isDefined), txs)
    assert(txs.forall(!_.has("reason")), "a transaction's reason is never given to the model")
    assertEquals(kit.wallet.calls(s"/wallets/$player/GBP/transactions").map(_.query.get("pageSize")), Vector(Some("10")))
    calledWithoutToken(kit.wallet, s"/wallets/$player/GBP/transactions")
  }

  // ── limits ────────────────────────────────────────────────────────────────

  Given("the player is on a break") { () =>
    kit.rg.answer("GET", s"/$player/limits", Answer.ok(Json.obj(
      "limits" -> Json.arr(Json.obj("limitType" -> "DEPOSIT_WEEKLY", "value" -> 20000L)), "pendingChanges" -> Json.arr(),
      "coolingOffUntil" -> "2026-12-01T00:00:00Z", "activeRestrictions" -> Json.arr("COOLING_OFF"))))
  }
  When("they ask the support agent about their limits") { () => ask("what are my limits?", Some("get_rg_status")) }
  Then("the reply states each limit, the break's end and whether they are excluded, read from responsible gambling") { () =>
    val s = result("get_rg_status")
    assertEquals((s.long("deposit_limit_amount"), s.text("deposit_limit_period"), s.text("cool_off_until"), s.bool("self_excluded")),
      (Some(20000L), Some("weekly"), Some("2026-12-01T00:00:00Z"), Some(false)))
    calledWithoutToken(kit.rg, s"/$player/limits")
  }

  // ── no tool for it ────────────────────────────────────────────────────────

  Given("the player asks something the support agent has no tool for") { () => () }
  When("the model answers") { () => ask("can you change my username?", None, "I can't help with that here.⟦handoff⟧") }
  Then("the reply says it cannot help with that and names the support chat") { () =>
    val body = io.eitheror.casino.support.config.ConfigBody()
    assertEquals(stream.text(), s"I can't help with that here.${body.handOff}")
    assert(body.handOff.contains("live chat"), body.handOff)
  }
  Then("it invents no figure") { () =>
    assert(!stream.text().matches(""".*[£€$]\s*\d.*"""), stream.text())
    assertEquals(requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten, Vector.empty)
  }

  // ── a read that does not answer ───────────────────────────────────────────

  Given("the wallet does not answer within the tool's time allowed") { () => kit.wallet.delayMillis = 1500L }
  When("the player asks the support agent for their balance") { () => ask("what is my balance?", Some("get_wallet_balance")) }
  Then("the reply says the balance cannot be read right now") { () =>
    assertEquals(resultText("get_wallet_balance"), Context.Unreadable)
  }
  Then("the turn does not fail") { () =>
    assertEquals(stream.status, 200)
    assertEquals(stream.names().filter(_ != "delta"), Vector("done"))
  }

  // ── personal fields ───────────────────────────────────────────────────────

  Given("the brand's configuration names no further personal field") { () => kit.configure(_.copy(personalFields = Set.empty)): Unit }
  When("the player asks the support agent about their account") { () => ask("what do you know about me?", Some("get_player_state")) }
  Then("the model is given the player's display name and no other personal field") { () =>
    assertEquals(result("get_player_state").text("displayName"), Some("Ann"))
    Seq("ann@example.com", "Lee", "1990-01-01", "Iceland").foreach(v => assert(!sent.contains(v), s"$v reached the model"))
  }

  Given("an operator names the player's email address in the brand's configuration") { () =>
    val now = kit.components.forKeyValueEntity(AgentConfiguration.id(kit.slug)).call(AgentConfiguration.get).invoke()
    AgentConfiguration.put(kit.components, kit.slug, io.eitheror.casino.support.config.ConfigChange(
      now.current.copy(personalFields = Set("email")), "op-1", kit.clock.instant())): Unit
  }
  When("the player starts a new conversation") { () => ask("what do you know about me?", Some("get_player_state")) }
  Then("the model may be given the player's email address") { () =>
    assertEquals(result("get_player_state").text("email"), Some("ann@example.com"))
  }
  Then("the configuration's change is audited") { () =>
    val last = kit.components.forKeyValueEntity(AgentConfiguration.id(kit.slug)).call(AgentConfiguration.get).invoke().recentChanges.last
    assertEquals((last.by, last.fields.contains("personalFields")), ("op-1", true))
  }
