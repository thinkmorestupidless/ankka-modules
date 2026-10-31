package io.eitheror.casino.kyc.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.{CountDeposit, KycRecord, Outcome}

import java.time.Instant

/**
 * features/kyc/reads.feature (US7): the operator's queue, by each filter of FR-035, with its live count;
 * the operator's read of one player; and the facts `players`' activity history (008's `ActivityView`) is
 * built from, asserted here as published.
 */
class ReadsFeatures extends KycFeatures("reads.feature"):

  /** The brand's players, one per state, named by the state. */
  private var players: Map[String, String] = Map.empty
  private var read: JsonNode               = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    players = Map.empty; read = null

  /** A filter as the backoffice names it, as the route takes it, and the count that goes with it. */
  private val Filters: Map[String, (String, String)] = Map(
    "docs awaiting review"   -> ("filter=awaiting", "awaiting"),
    "KYC pending"            -> ("filter=pending", "pending"),
    "verified"               -> ("filter=verified", "verified"),
    "rejected"               -> ("filter=rejected", "rejected"),
    "review required"        -> ("filter=flagged&reason=large_deposit", "largeDeposit"),
    "ready for final review" -> ("filter=ready", "ready")
  )

  /**
   * Which of the brand's players each filter lists, and which it must not. An override leaves a document
   * awaiting review, so the verified and rejected players are not excluded from that filter.
   */
  private val Expected: Map[String, (String, Set[String])] = Map(
    "docs awaiting review"   -> ("awaiting", Set("flagged", "ready")),
    "KYC pending"            -> ("awaiting", Set("verified", "rejected", "flagged")),
    "verified"               -> ("verified", Set("awaiting", "rejected", "flagged", "ready")),
    "rejected"               -> ("rejected", Set("awaiting", "verified", "flagged", "ready")),
    "review required"        -> ("flagged", Set("awaiting", "verified", "rejected", "ready")),
    "ready for final review" -> ("ready", Set("awaiting", "verified", "rejected", "flagged"))
  )

  private def queueRead(query: String): JsonNode =
    val r = kit.request("GET", kit.operatorPath(s"/queue?$query"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json

  private def rowsOf(q: JsonNode): Vector[JsonNode] = q.objects("rows")
  private def ids(q: JsonNode): Set[String]         = rowsOf(q).flatMap(_.text("playerId")).toSet

  private def overrideOf(p: String, to: String): Unit =
    assertEquals(kit.json("POST", kit.operatorPath(s"/$p/override"), json("status" -> to, "reason" -> "checked"), kit.asOperator()).status, 200)

  // ── the queue ─────────────────────────────────────────────────────────────────────────────────

  Given("the brand's players") { () =>
    val awaiting = newPlayer(); submit("identity_front", as = awaiting): Unit
    val verified = newPlayer(); submit("identity_front", as = verified); overrideOf(verified, "verified")
    val rejected = newPlayer(); submit("identity_front", as = rejected); overrideOf(rejected, "rejected")
    val flagged  = newPlayer()
    kit.components.forEventSourcedEntity(EntityId(flagged)).call(KycRecord.countDeposit)
      .invoke(CountDeposit(flagged, "EUR", 300000, 1, Profiles.Mga, kit.now())): Unit
    val ready = player
    profile()
    assertEquals(setRequired("identity_front").status, 200, last.text)
    reviewed(submit("identity_front"), Outcome.Approved)
    assert(state().readyAt.isDefined)
    players = Map("awaiting" -> awaiting, "verified" -> verified, "rejected" -> rejected, "flagged" -> flagged, "ready" -> ready)
  }

  private var filter = ""

  When("the queue is read with the filter {string}") { (name: String) => filter = name }

  Then("the players matching are listed with a live count") { () => listed(): Unit }

  Then("the players matching are listed with a live count and the time since each became ready") { () =>
    val row = listed()
    assertEquals(row.text("readyAt").map(Instant.parse), state().readyAt)
    assert(row.long("sinceSeconds").exists(_ >= 0), row.toString)
  }

  /** The filter lists its player and none of the others, with a count equal to its rows; the player's row. */
  private def listed(): JsonNode =
    val (query, count)     = Filters(filter)
    val (listedAs, absent) = Expected(filter)
    val expected           = players(listedAs)
    val others             = absent.map(players)
    val q = eventually(s"$expected under '$filter'")(Some(queueRead(query)).filter(q => ids(q)(expected) && (ids(q) & others).isEmpty))
    assertEquals(q.get("counts").get(count).asLong, rowsOf(q).size.toLong, q.get("counts").toString)
    rowsOf(q).find(_.text("playerId").contains(expected)).get

  // ── one player ────────────────────────────────────────────────────────────────────────────────

  Given("a player with documents") { () =>
    policy("kycWindowDays" -> 30)
    kit.publish(CasinoPlayers, player, PlayerRegistered(player, kit.now(), None, None, None, Some("MT"), Some("EUR"), None, kit.now()))
    eventually("the deadline")(state().deadline)
    reviewed(submit("identity_front"), Outcome.Rejected, Some("document_unclear"))
    submit("identity_front"): Unit
    submit("proof_of_address"): Unit
    kit.components.forEventSourcedEntity(EntityId(player)).call(KycRecord.countDeposit)
      .invoke(CountDeposit(player, "EUR", 5000, 1, Profiles.Curacao, kit.now())): Unit
  }

  private def policy(fields: (String, Any)*): Unit =
    assertEquals(kit.json("PUT", kit.operatorPath("/policy"), json(fields*), kit.asOperator()).status, 200)

  When("an operator reads the player's KYC tab") { () => read = operatorRead() }

  Then("every document is listed with category, status, upload time, review time, reviewer and reason code") { () =>
    // each category's current document, as the original's tab shows it; the rejected front is history
    val shown = read.objects("categories").flatMap(c => Option(c.get("document")).filter(_.isObject)) ++ read.objects("other")
    assertEquals(shown.flatMap(_.text("category")).toSet, Set("identity_front", "proof_of_address"))
    for d <- shown do
      assert(Seq("documentId", "category", "status", "uploadedAt").forall(f => d.text(f).isDefined), d.toString)
      assertEquals(d.text("status"), Some("pending"))
    reviewed(documentOf(read, "proof_of_address").get.text("documentId").get, Outcome.Rejected, Some("address_mismatch"))
    val after = documentOf(operatorRead(), "proof_of_address").get
    assertEquals((after.text("status"), after.text("reviewedBy"), after.text("reasonCode")), (Some("rejected"), Some("op-1"), Some("address_mismatch")))
    assert(after.text("reviewedAt").isDefined)
  }

  Then("the account's KYC status, mode, required categories, source of funds status, deadline and lifetime deposits are shown") { () =>
    read = operatorRead()
    assertEquals(read.text("status"), Some(state().status.wire))
    assertEquals(read.text("mode"), Some("automatic"))
    assert(read.get("requiredOfPlayer").isArray)
    assertEquals(read.text("sof"), Some("unverified"))
    assertEquals(read.get("deadline").get("at").asText, state().deadline.get.at.toString)
    assertEquals(read.get("depositsByCurrency").get("EUR").asLong, 5000L)
  }

  // ── facts for players' activity history ───────────────────────────────────────────────────────

  Given("a player whose KYC status has changed") { () =>
    submit("identity_front"): Unit
    overrideStatus("verified"): Unit
  }

  When("their activity history is read") { () => () }

  Then("the KYC status changes appear as account entries") { () =>
    // 008's ActivityView files these families as account entries; here they are as `players` reads them.
    eventually("the pending fact")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerKycPending if f.playerId == player => f })
    val v = eventually("the verified fact")(kit.facts(CasinoPlayers).collectFirst { case f: PlayerKycVerified if f.playerId == player => f })
    assertEquals((v.previousValue, v.newValue, v.actor, v.actorId), ("pending", "verified", "operator", Some("op-1")))
  }
