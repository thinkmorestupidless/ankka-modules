package io.eitheror.casino.markets.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import com.thinkmorestupidless.predictions.markets.MarketState
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.markets.Json
import io.eitheror.casino.markets.it.{Kit, Reply, SharedKit}

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*

/**
 * A feature file of features/predictions bound against the whole service (constitution VII): every
 * operator's call through the operator's routes, the book's opening as the fake exchange records it.
 * A market's name in a scenario is suffixed per scenario, since the database outlives scenarios.
 */
abstract class MarketsFeatures(file: String) extends GherkinSuite(s"../../features/predictions/$file") with LogCapturing:
  override val munitTimeout: scala.concurrent.duration.Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit

  protected var run         = ""
  protected var operator    = Seq.empty[(String, String)]
  protected var last: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    run = UUID.randomUUID().toString.take(8)
    operator = kit.asOperator()
    last = null

  protected def id(name: String): String = s"$name-$run"

  Given("a brand running the module {string}") { (module: String) => assertEquals(module, "predictions") }

  Given("an operator of the brand with the markets permission") { () => operator = kit.asOperator() }

  Given("an operator of the brand without the markets permission") { () =>
    operator = kit.asOperator("op-2", s"brand_${kit.slug}")
  }

  /** A definition the brand accepts, closing a day from now; `fields` replace its own. */
  protected def draft(name: String, fields: (String, Any)*): Reply =
    val base = Seq[(String, Any)](
      "market"             -> id(name),
      "question"           -> s"Will $name happen?",
      "description"        -> "A market of the lifecycle feature",
      "category"           -> kit.base.categories.head,
      "outcomes"           -> Vector("yes", "no"),
      "currency"           -> kit.base.currencies.head,
      "close"              -> kit.now().plus(Duration.ofDays(1)).toString,
      "expectedResolution" -> kit.now().plus(Duration.ofDays(2)).atZone(java.time.ZoneOffset.UTC).toLocalDate.toString,
      "criteria"           -> "As the authority reports it",
      "authority"          -> "The authority"
    )
    val named = fields.map(_._1).toSet
    last = kit.json("POST", kit.operatorPath("/drafts"), Json.obj((base.filterNot(f => named(f._1)) ++ fields)*), operator)
    last

  protected def command(name: String, cmd: String, fields: (String, Any)*): Reply =
    last = kit.json("POST", kit.operatorPath(s"/${id(name)}/$cmd"), Json.obj(fields*), operator)
    last

  protected def ok(r: Reply): Unit = assert(r.status == 200, s"${r.status}: ${r.text}")

  protected val evidence: Vector[JsonNode] =
    Vector(Json.obj("source" -> "The authority", "locator" -> "https://example.org/result", "excerpt" -> "The result"))

  protected def state(name: String): MarketState = kit.market(id(name)).state

class LifecycleFeatures extends MarketsFeatures("lifecycle.feature"):

  When("the operator drafts a market {string} with the outcomes {string} and {string}") { (m: String, a: String, b: String) =>
    draft(m, "outcomes" -> Vector(a, b)): Unit
  }

  When("the operator approves market {string}") { (m: String) => ok(command(m, "approve")) }

  When("the operator closes market {string}") { (m: String) => ok(command(m, "close", "reason" -> "The question was settled early")) }

  When("the operator resolves market {string} with the outcome {string}") { (m: String, o: String) =>
    ok(command(m, "resolve", "outcome" -> o, "evidence" -> evidence, "authority" -> "The authority"))
  }

  When("the operator voids market {string}") { (m: String) =>
    ok(command(m, "void", "reason" -> "The question cannot be settled", "evidence" -> evidence))
  }

  Then("market {string} is resolved with the outcome {string}") { (m: String, o: String) =>
    val market = kit.market(id(m))
    assertEquals(market.state, MarketState.Resolved)
    assertEquals(market.resolution.map(_.outcome.value), Some(o))
  }

  Then("market {string} is void") { (m: String) => assertEquals(state(m), MarketState.Void) }

  Then("it is refused naming the markets permission") { () =>
    assertEquals(last.status, 403, last.text)
    assert(last.text.contains("markets permission"), last.text)
  }

  Then("a fact for each change to market {string} is written on the brand's markets topic, keyed by the market") { (m: String) =>
    val market = id(m)
    val facts = kit.eventually("the draft, approval and opening as facts") {
      val mine = kit.facts(MarketsEvents).filter(_._2.marketId == market)
      Option.when(mine.size >= 3)(mine)
    }
    assertEquals(facts.map(_._2.getClass.getSimpleName), Vector("MarketDrafted", "MarketApproved", "MarketOpened"))
    assert(facts.forall(_._1.contains(market)), facts.map(_._1).toString)
  }

  Given("the brand offers the categories {string} and {string}") { (a: String, b: String) =>
    kit.reset(kit.base.copy(categories = Vector(a, b)))
  }

  When("the operator drafts a market {string} in the category {string}") { (m: String, c: String) => draft(m, "category" -> c): Unit }

  Then("it is refused naming the category") { () =>
    assertEquals(last.status, 400, last.text)
    assertEquals(last.str("error"), "category")
    assert(last.str("category").nonEmpty, last.text)
  }

  Given("the brand's wallet holds {string} only") { (c: String) => kit.reset(kit.base.copy(currencies = Vector(c))) }

  When("the operator drafts a market {string} in {string}") { (m: String, c: String) => draft(m, "currency" -> c): Unit }

  Then("it is refused naming the currency") { () =>
    assertEquals(last.status, 400, last.text)
    assertEquals(last.str("error"), "currency")
    assert(last.str("currency").nonEmpty, last.text)
  }

  Given("the brand's limits are a maximum order cost of {string} and a maximum position of {string}") { (cost: String, pos: String) =>
    kit.reset(kit.base.copy(maxOrderCost = cost.toLong, maxPosition = pos.toLong))
  }

  When("the operator drafts a market {string} and approves it") { (m: String) =>
    ok(draft(m))
    ok(command(m, "approve"))
  }

  When("the operator drafts a market {string} with a maximum position of {string} and approves it") { (m: String, pos: String) =>
    ok(draft(m, "maxPosition" -> pos.toLong))
    ok(command(m, "approve"))
  }

  Then("the book of market {string} opens with a maximum order cost of {string} and a maximum position of {string}") {
    (m: String, cost: String, pos: String) =>
      val open = kit.eventually(s"the book of $m opened") {
        kit.exchange.callsFor(id(m)).find(_.command == "open")
      }
      assertEquals(open.body.get("maxOrderCost").asLong, cost.toLong)
      assertEquals(open.body.get("maxPosition").asLong, pos.toLong)
      assertEquals(open.body.get("unit").asLong, 100L)
  }
