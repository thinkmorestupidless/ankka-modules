package io.eitheror.casino.payouts.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.terminals.Terminal
import io.eitheror.casino.payouts.it.FakeGateway
import io.eitheror.casino.payouts.request.{PayoutStatus, Status}

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/**
 * features/payouts/reads.feature (US6; FR-014). The player reads through their own route; the operator reads
 * the brand's queue, which holds every test's requests, so a filter is checked by every row it returns and by
 * where this scenario's requests fall.
 */
class ReadsFeatures extends PayoutsFeatures("reads.feature"):

  private val polled: Terminal = terminal("t-polled", "unityfinance", destination = true).copy(hasStatus = true)

  private var ids: Map[String, String] = Map.empty
  private var rows: Vector[JsonNode]   = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    ids = Map.empty
    rows = Vector.empty

  // ── the player's own list ─────────────────────────────────────────────────────────────────────

  Given("three withdrawal requests over a player's life") { () =>
    val first = pending(amount = 1000L)
    assertEquals(kit.request("DELETE", kit.playerPath(s"/$player/withdrawal-requests/$first"), None, kit.asPlayer(player)).status, 200)
    kit.advance(JDuration.ofSeconds(1))
    val second = pending(amount = 2000L)
    assertEquals(reject(second, "documents unclear").status, 200, last.text)
    kit.advance(JDuration.ofSeconds(1))
    val third = pending(amount = 3000L)
    ids = Map("first" -> first, "second" -> second, "third" -> third)
  }

  When("the player lists them") { () =>
    rows = eventually("three listed")(Some(requests().objects("requests")).filter(_.size == 3))
  }

  Then("all three are shown newest first with amount, terminal, time and status") { () =>
    assertEquals(rows.flatMap(_.text("requestId")), Vector(ids("third"), ids("second"), ids("first")))
    assertEquals(rows.flatMap(_.long("amount")), Vector(3000L, 2000L, 1000L))
    assertEquals(rows.flatMap(_.text("status")), Vector("PENDING_REVIEW", "REJECTED", "CANCELLED"))
    assert(rows.forall(r => r.text("terminalName").exists(_.nonEmpty) && r.text("createdAt").isDefined), rows.mkString("\n"))
    val times = rows.flatMap(_.text("createdAt")).map(java.time.Instant.parse)
    assertEquals(times, times.sorted.reverse)
  }

  // ── the operator's queue ─────────────────────────────────────────────────────────────────────

  Given("a queue with requests pending review, approved and initiated, and marked needs review") { () =>
    val waiting = pending(to = polled)
    kit.gateway.initiateFor(waiting, FakeGateway.answer(waiting, "initiated", Some(s"ref-$waiting")))
    assertEquals(approve(waiting).status, 200, last.text)
    player = newPlayer()
    val doubtful = pending(to = polled)
    kit.gateway.initiateFor(doubtful, FakeGateway.answer(doubtful, "initiated", Some(s"ref-$doubtful")))
    kit.gateway.statusFor(doubtful, FakeGateway.answer(doubtful, "no_record"))
    assertEquals(approve(doubtful).status, 200, last.text)
    player = newPlayer()
    val review = pending()
    eventually("needs review", 20.seconds)(Some(requestState(doubtful)).filter(_.payoutStatus == PayoutStatus.NeedsReview))
    assertEquals(requestState(waiting).payoutStatus, PayoutStatus.Initiated)
    ids = Map("pending" -> review, "initiated" -> waiting, "needs review" -> doubtful)
  }

  private def filtered(query: String, expect: String): Unit =
    rows = eventually(s"$expect listed")(Some(operator("GET", s"?$query&pageSize=200")).filter(_.status == 200)
      .map(_.json.objects("requests")).filter { rs =>
        // The view follows the requests; the scenario's three settle into their rows within moments.
        val shown = rs.flatMap(_.text("requestId")).toSet
        ids.forall((name, id) => shown(id) == (name == expect))
      })

  private def only(expect: String): Set[String] =
    val shown = rows.flatMap(_.text("requestId")).toSet
    assert(shown(ids(expect)), s"$expect not shown")
    ids.view.filterKeys(_ != expect).values.foreach(id => assert(!shown(id), s"$id shown"))
    shown

  When("an operator filters it by status {string}") { (status: String) =>
    filtered(s"status=${status.toUpperCase.replace(' ', '_')}", "pending")
  }

  Then("only the requests pending review are shown") { () =>
    only("pending"): Unit
    assert(rows.forall(_.text("status").contains(Status.PendingReview.upper)), rows.mkString("\n"))
  }

  When("an operator filters it by the withdrawal payment's status {string}") { (status: String) =>
    filtered(s"status=ALL&payoutStatus=${status.toUpperCase.replace(' ', '_')}", "needs review")
  }

  Then("only the requests marked needs review are shown") { () =>
    only("needs review"): Unit
    assert(rows.forall(_.text("payoutStatus").contains(PayoutStatus.NeedsReview.upper)), rows.mkString("\n"))
  }
