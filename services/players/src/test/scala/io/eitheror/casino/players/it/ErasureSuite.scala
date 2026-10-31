package io.eitheror.casino.players.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.erasure.{ErasureFlow, ErasureWorkflow}
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCasesView, FraudCaseStatus}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Status}

import java.time.Duration
import scala.concurrent.duration.*

/** T066 and T067: erasure and the export through the routes and the workflows (FR-038 to FR-040, SC-006). */
class ErasureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def player(id: String): PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def flow(id: String): ErasureFlow   = ErasureWorkflow.stateOf(kit.components, id)
  private def op(method: String, path: String): Reply =
    kit.request(method, kit.operatorPath(path), if method == "GET" then None else Some("{}"), kit.asOperator(subject = "op-6"))

  /** A player closed by an operator; the address, the id and a token taken before the closure. */
  private def closed(fields: (String, Any)*): (String, String, String) =
    val (email, id) = kit.activePlayer(fields*)
    val token = kit.signIn(email).str("token")
    assertEquals(op("POST", s"/players/$id/close").status, 200)
    (email, id, token)

  private def past(hold: Duration = Duration.ofDays(ErasureWorkflow.DefaultRetentionDays.toLong + 1)): Unit = kit.advance(hold)

  private def merchantErasures(id: String): Vector[String] =
    kit.wallet.received.map(_.path).filter(_ == s"/erasure/players/${kit.slug}:$id")

  test("an active player's erasure is 409 not_closed; a closed player's 202 with the correlation id and the hold from the closure") {
    val (email, id) = kit.activePlayer()
    val token = kit.signIn(email).str("token")
    val refused = kit.request("POST", kit.playerPath(s"/$id/erasure"), Some("{}"), kit.asToken(token))
    assertEquals((refused.status, refused.str("error")), (409, "not_closed"))
    assertEquals(op("POST", s"/players/$id/close").status, 200)
    val r = kit.request("POST", kit.playerPath(s"/$id/erasure"), Some("{}"), kit.asToken(token))
    assertEquals(r.status, 202, r.text)
    val hold = player(id).closedAt.get.plus(Duration.ofDays(ErasureWorkflow.DefaultRetentionDays.toLong))
    assertEquals((r.str("holdUntil"), player(id).status, player(id).erasure.map(_.correlationId)), (hold.toString, Status.ErasurePending, Some(r.str("correlationId"))))
    assertEquals(kit.request("GET", kit.playerPath(s"/$id"), None, kit.asToken(token)).str("erasureHoldUntil"), hold.toString)
    assertEquals(kit.request("POST", kit.playerPath(s"/$id/erasure"), Some("{}"), kit.asToken(token)).str("correlationId"), r.str("correlationId"))
    Thread.sleep(1000)
    assertEquals((kit.wallet.erasures, merchantErasures(id), flow(id).status, flow(id).heldFor), (Vector.empty, Vector.empty, "held", Some("retention")))
  }

  test("past the hold: every part once, the realm user removed, erased, the certificates readable; personal fields gone, money unchanged") {
    val (email, id, _) = closed("firstName" -> "Erin", "lastName" -> "Vale", "dateOfBirth" -> "1980-01-02")
    kit.publish(MoneyTransactions, s"$id/EUR", DepositConfirmed(id, Currency.unsafe("EUR"), s"t-$id", MinorUnits.unsafe(1234), "ref", 1,
      SignedMinorUnits(1234), MinorUnits.unsafe(0), kit.now()))
    kit.eventually("the wallet row")(io.eitheror.casino.players.reads.WalletSummaryView.ofPlayer(kit.views, id).headOption)
    assertEquals(op("POST", s"/players/$id/erasure").status, 202)
    past()
    kit.eventually("erased", 20.seconds)(Option.when(flow(id).step == ErasureFlow.Erased)(()))
    assertEquals((kit.wallet.erasures.count(_ == id), kit.kyc.erasures.count(_ == id), kit.payouts.erasures.count(_ == id), merchantErasures(id).size), (1, 1, 1, 1))
    assert(kit.realm.receivedOf("delete").nonEmpty)
    assertEquals(kit.realm.userByEmail(email), None)
    val p = player(id)
    assertEquals((p.status, p.profile.get("firstName"), p.emails.map(_.address.reveal).distinct), (Status.Erased, None, Vector("")))
    val e = op("GET", s"/players/$id/erasure")
    assertEquals((e.status, e.str("status")), (200, "erased"))
    val services = (0 until e.json.get("certificates").size).map(i => e.json.get("certificates").get(i).get("service").asText).toVector
    assertEquals(services, Vector("wallet", "responsible-gambling", "kyc", "payouts", "merchant", "players"))
    assertEquals(kit.rg.received.count(_.path == s"/v1/casino/brands/${kit.slug}/rg/erasure/players/$id"), 1)
    assertEquals(io.eitheror.casino.players.reads.WalletSummaryView.ofPlayer(kit.views, id).map(_.realBalanceAfter), Vector(1234L))
    val again = kit.register("email" -> email)
    assertEquals(again.status, 201, again.text)
    assertNotEquals(again.str("playerId"), id)
  }

  test("past the hold: the dismissal notes of a case linking the player are blanked; the case stays dismissed") {
    val names = Seq[(String, Any)]("firstName" -> s"Fe${java.util.UUID.randomUUID().toString.take(8)}", "lastName" -> "Lin", "dateOfBirth" -> "1985-03-04")
    kit.activePlayer(names*): Unit
    val (_, id, _) = closed(names*)
    val caseId = kit.eventually("the case", 10.seconds)(FraudCasesView.ofPlayer(kit.views, id).headOption).caseId
    val d = kit.request("PATCH", kit.operatorPath(s"/fraud-cases/$caseId/dismiss"), Some("""{"notes":"siblings sharing a flat"}"""),
      kit.asOperator(subject = "op-6"))
    assertEquals((d.status, d.str("reviewNotes")), (200, "siblings sharing a flat"))
    assertEquals(op("POST", s"/players/$id/erasure").status, 202)
    past()
    kit.eventually("erased", 20.seconds)(Option.when(flow(id).step == ErasureFlow.Erased)(()))
    val s = kit.components.forEventSourcedEntity(EntityId(caseId)).call(FraudCaseEntity.get).invoke()
    assertEquals((s.reviewNotes.reveal, s.status), ("", FraudCaseStatus.Dismissed))
    val row = kit.eventually("the dismissed case listed", 10.seconds) {
      val listed = op("GET", "/fraud-cases?status=DISMISSED").json.get("cases")
      (0 until listed.size).map(listed.get).find(_.get("caseId").asText == caseId)
    }
    assert(row.get("reviewNotes").isNull, row.toString)
  }

  test("an open withdrawal holds the erasure past its date until it settles, saying so") {
    val (_, id, _) = closed()
    val eur = Currency.unsafe("EUR")
    kit.publish(CasinoWithdrawals, id, WithdrawalRequested(id, "w-9", MinorUnits.unsafe(500), eur, "t", kit.now()))
    kit.eventually("the withdrawal open")(Option.when(player(id).withdrawalOpen)(()))
    assertEquals(op("POST", s"/players/$id/erasure").status, 202)
    past()
    kit.eventually("held for the withdrawal", 5.seconds)(Option.when(flow(id).heldFor.contains(ErasureFlow.OpenWithdrawal))(()))
    Thread.sleep(1000)
    assertEquals((flow(id).status, kit.wallet.erasures.contains(id)), ("held", false))
    kit.publish(CasinoWithdrawals, id, WithdrawalCompleted(id, "w-9", MinorUnits.unsafe(500), eur, kit.now()))
    kit.eventually("erased", 20.seconds)(Option.when(flow(id).step == ErasureFlow.Erased)(()))
  }

  test("past the hold, a target failing after its retries: failed naming it, the earlier certificate kept, nothing of this service's own erased") {
    val (email, id, _) = closed("firstName" -> "Fay", "lastName" -> "Lorn", "dateOfBirth" -> "1979-05-06")
    kit.wallet.failingErasures.add(s"${kit.slug}:$id")
    assertEquals(op("POST", s"/players/$id/erasure").status, 202)
    past()
    kit.eventually("failed", 90.seconds)(Option.when(flow(id).step == ErasureFlow.Failed)(()))
    assertEquals((flow(id).failedAt, flow(id).certificates.map(_.service)), (Some("merchant"), Vector("wallet", "responsible-gambling", "kyc", "payouts")))
    assertEquals(kit.wallet.erasures.count(_ == id), 1)
    assert(merchantErasures(id).size > 1, "the merchant's part was retried")
    val p = player(id)
    assertEquals((p.status, p.profile.get("firstName")), (Status.ErasurePending, Some("Fay")))
    assert(kit.realm.userByEmail(email).nonEmpty, "the realm user is kept")
    val e = op("GET", s"/players/$id/erasure")
    assertEquals((e.status, e.str("status"), e.str("failedAt")), (200, "failed", "merchant"))
  }

  test("an operator's erasure read: 404 erasure_not_found before one is filed") {
    val (_, id) = kit.activePlayer()
    assertEquals(op("GET", s"/players/$id/erasure").str("error"), "erasure_not_found")
  }

  test("the export: 202, then the profile, consents, sessions, activity and messages, each service's part, no note; 410 after 24 h") {
    val (email, id) = kit.activePlayer("firstName" -> "Pia")
    val token = kit.signIn(email).str("token")
    assertEquals(op("POST", s"/players/$id/notes").status, 400)
    kit.request("POST", kit.operatorPath(s"/players/$id/notes"), Some("""{"content":"export-note-secret"}"""), kit.asOperator()): Unit
    kit.request("POST", kit.operatorPath(s"/players/$id/messages"), Some("""{"category":"service","bodyTemplate":"export message"}"""),
      kit.asOperator()): Unit
    kit.eventually("the message listed")(io.eitheror.casino.players.inbox.MessagesView.ofPlayer(kit.views, id, 0, 10).headOption)
    val r = kit.request("POST", kit.playerPath(s"/$id/exports"), Some("{}"), kit.asToken(token))
    assertEquals(r.status, 202, r.text)
    val exportId = r.str("exportId")
    def read = kit.request("GET", kit.playerPath(s"/$id/exports/$exportId"), None, kit.asToken(token))
    val first = read.status
    assert(first == 409 || first == 200, s"$first")
    val doc = kit.eventually("the export", 15.seconds)(Option(read).filter(_.status == 200)).json
    Seq("profile", "consents", "sessions", "activity", "messages").foreach(f => assert(doc.has(f), f))
    assertEquals(doc.get("profile").get("firstName").asText, "Pia")
    assert(doc.get("messages").size >= 1)
    assertEquals((doc.get("parts").has("wallet"), doc.get("parts").has("responsible-gambling"), doc.get("parts").has("kyc"),
      doc.get("parts").has("payouts"), doc.get("parts").has("merchant"), doc.get("missing").size), (true, true, true, true, true, 0))
    assert(!Json.render(doc).contains("export-note-secret"))
    val (otherEmail, other) = kit.activePlayer()
    assertEquals(kit.request("GET", kit.playerPath(s"/$other/exports/$exportId"), None, kit.asToken(kit.signIn(otherEmail).str("token"))).status, 404)
    assertEquals(op("GET", s"/exports/$exportId").status, 200)
    kit.advance(Duration.ofHours(25))
    assertEquals(read.status, 410)
  }

  test("a wallet that does not answer the export is named missing; the operator's activity-export is the same shape") {
    val (_, id) = kit.activePlayer()
    kit.wallet.silent = true
    try
      val r = op("POST", s"/players/$id/activity-export")
      assertEquals(r.status, 202, r.text)
      val doc = kit.eventually("the export", 30.seconds)(Option(op("GET", s"/exports/${r.str("exportId")}")).filter(_.status == 200)).json
      assertEquals((0 until doc.get("missing").size).map(doc.get("missing").get(_).asText).toSet, Set("wallet", "merchant"))
      assert(doc.has("profile"))
    finally kit.wallet.silent = false
  }
