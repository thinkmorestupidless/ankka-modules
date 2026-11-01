package io.eitheror.casino.markets.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.predictions.markets.MarketState
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.markets.Json

import java.time.{Duration as JDuration, ZoneOffset}
import scala.concurrent.duration.*

/**
 * The library's timers over Postgres and a restart (T015): a market approved before its open time opens
 * and closes on time though the service was down when it was due, and each change is one fact. A kit of
 * its own, since it restarts the service.
 */
class LifecycleItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private lazy val kit: Kit = Kit()

  override def afterAll(): Unit = kit.stop()

  test("the timers open and close a market on time after a restart, and each change is one fact") {
    val market = "timed-1"
    val start  = kit.now()
    val draft = Json.obj(
      "market"             -> market,
      "question"           -> "Will the timers hold?",
      "description"        -> "Opens and closes by itself",
      "category"           -> "politics",
      "outcomes"           -> Vector("yes", "no"),
      "currency"           -> "EUR",
      "openTime"           -> start.plusSeconds(4).toString,
      "close"              -> start.plusSeconds(10).toString,
      "expectedResolution" -> start.plus(JDuration.ofDays(1)).atZone(ZoneOffset.UTC).toLocalDate.toString,
      "criteria"           -> "As reported",
      "authority"          -> "The authority"
    )
    val drafted = kit.json("POST", kit.operatorPath("/drafts"), draft, kit.asOperator())
    assertEquals(drafted.status, 200, drafted.text)
    val approved = kit.json("POST", kit.operatorPath(s"/$market/approve"), Json.obj(), kit.asOperator())
    assertEquals(approved.status, 200, approved.text)
    assertEquals(kit.market(market).state, MarketState.Approved)

    // Down across the open time: the timer's row outlives the service, and it fires when it is back.
    kit.testKit.restartService(downFor = 5.seconds)

    kit.eventually("open after the restart", 60.seconds)(Option.when(kit.market(market).state == MarketState.Open)(()))
    kit.eventually("closed at its close", 60.seconds)(Option.when(kit.market(market).state == MarketState.Closed)(()))

    val kinds = kit.eventually("the four facts") {
      val mine = kit.facts(MarketsEvents).filter(_._2.marketId == market).map(_._2.getClass.getSimpleName)
      Option.when(mine.size >= 4)(mine)
    }
    Thread.sleep(1000) // a redelivered change would land here
    assertEquals(kit.facts(MarketsEvents).filter(_._2.marketId == market).map(_._2.getClass.getSimpleName), kinds)
    assertEquals(kinds, Vector("MarketDrafted", "MarketApproved", "MarketOpened", "MarketClosed"))
    assertEquals(kit.exchange.callsFor(market).map(_.command), Vector("open", "close"))
  }

  test("a market's limits change only when the library takes the draft or the change, and a change keeps a limit it leaves out") {
    val market = "limits-1"
    val start  = kit.now()
    def draft(position: Long) = Json.obj(
      "market"             -> market,
      "question"           -> "Will the limits hold?",
      "description"        -> "Limits",
      "category"           -> "politics",
      "outcomes"           -> Vector("yes", "no"),
      "currency"           -> "EUR",
      "openTime"           -> start.plus(JDuration.ofDays(1)).toString,
      "close"              -> start.plus(JDuration.ofDays(2)).toString,
      "expectedResolution" -> start.plus(JDuration.ofDays(3)).atZone(ZoneOffset.UTC).toLocalDate.toString,
      "criteria"           -> "As reported",
      "authority"          -> "The authority",
      "maxOrderCost"       -> 5000L,
      "maxPosition"        -> position
    )
    def limits = kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(market))
      .call(io.eitheror.casino.markets.terms.MarketTerms.get).invoke()
    assertEquals(kit.json("POST", kit.operatorPath("/drafts"), draft(50), kit.asOperator()).status, 200)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$market/approve"), Json.obj(), kit.asOperator()).status, 200)
    val again = kit.json("POST", kit.operatorPath("/drafts"), draft(10), kit.asOperator())
    assert(again.status / 100 == 4, again.text)
    assertEquals((limits.maxOrderCost, limits.maxPosition), (Some(5000L), Some(50L)))
  }

  test("a change that leaves a limit out keeps the drafted one") {
    val market = "limits-2"
    val start  = kit.now()
    val base = Vector[(String, Any)](
      "question"           -> "Will the change keep it?",
      "description"        -> "Limits",
      "category"           -> "politics",
      "outcomes"           -> Vector("yes", "no"),
      "currency"           -> "EUR",
      "openTime"           -> start.plus(JDuration.ofDays(1)).toString,
      "close"              -> start.plus(JDuration.ofDays(2)).toString,
      "expectedResolution" -> start.plus(JDuration.ofDays(3)).atZone(ZoneOffset.UTC).toLocalDate.toString,
      "criteria"           -> "As reported",
      "authority"          -> "The authority"
    )
    def limits = kit.components.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(market))
      .call(io.eitheror.casino.markets.terms.MarketTerms.get).invoke()
    val drafted = kit.json("POST", kit.operatorPath("/drafts"), Json.obj((("market" -> market) +: base :+ ("maxPosition" -> 50L))*), kit.asOperator())
    assertEquals(drafted.status, 200, drafted.text)
    val changed = kit.json("POST", kit.operatorPath(s"/$market/change"), Json.obj((base.map { case ("description", _) => "description" -> "Limits, changed"; case f => f } :+ ("maxOrderCost" -> 7000L))*), kit.asOperator())
    assertEquals(changed.status, 200, changed.text)
    assertEquals((limits.maxOrderCost, limits.maxPosition), (Some(7000L), Some(50L)))
    val alone = kit.json("POST", kit.operatorPath(s"/$market/change"), Json.obj((base.map { case ("description", _) => "description" -> "Limits, changed"; case f => f } :+ ("maxPosition" -> 40L))*), kit.asOperator())
    assertEquals(alone.status, 200, alone.text)
    assertEquals((limits.maxOrderCost, limits.maxPosition), (Some(7000L), Some(40L)))
  }

  test("a change naming a limit is refused once the market's book has opened") {
    val market = "limits-3"
    val start  = kit.now()
    val base = Vector[(String, Any)](
      "question"           -> "Will the book keep its limits?",
      "description"        -> "Open",
      "category"           -> "politics",
      "outcomes"           -> Vector("yes", "no"),
      "currency"           -> "EUR",
      "openTime"           -> start.plusSeconds(2).toString,
      "close"              -> start.plus(JDuration.ofDays(2)).toString,
      "expectedResolution" -> start.plus(JDuration.ofDays(3)).atZone(ZoneOffset.UTC).toLocalDate.toString,
      "criteria"           -> "As reported",
      "authority"          -> "The authority"
    )
    assertEquals(kit.json("POST", kit.operatorPath("/drafts"), Json.obj((("market" -> market) +: base)*), kit.asOperator()).status, 200)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$market/approve"), Json.obj(), kit.asOperator()).status, 200)
    kit.eventually("open", 60.seconds)(Option.when(kit.market(market).state == MarketState.Open)(()))
    val r = kit.json("POST", kit.operatorPath(s"/$market/change"), Json.obj((base :+ ("maxPosition" -> 40L))*), kit.asOperator())
    assertEquals((r.status, r.str("error"), r.str("state")), (409, "state", "open"), r.text)
  }
