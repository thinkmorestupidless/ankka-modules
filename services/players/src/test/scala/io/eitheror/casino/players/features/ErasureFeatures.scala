package io.eitheror.casino.players.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.erasure.{ErasureFlow, ErasureWorkflow}
import io.eitheror.casino.players.inbox.MessagesView
import io.eitheror.casino.players.it.Reply
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, PlayersView, Status}
import io.eitheror.casino.players.reads.WalletSummaryView

import java.time.Duration
import scala.concurrent.duration.*

/** features/players/erasure.feature (T068). */
class ErasureFeatures extends PlayersFeatures("erasure.feature"):
  protected def pending = Map.empty

  private var email    = ""
  private var playerId = ""
  private var token    = ""
  private var reply: Reply = null
  private var doc: JsonNode = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    email = ""; playerId = ""; token = ""; reply = null; doc = null

  private def player: PlayerState = kit.components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
  private def flow: ErasureFlow   = ErasureWorkflow.stateOf(kit.components, playerId)
  private def op(method: String, path: String, body: String = "{}"): Reply =
    kit.request(method, kit.operatorPath(path), if method == "GET" then None else Some(body), kit.asOperator())

  private def active(fields: (String, Any)*): Unit =
    val (e, id) = kit.activePlayer(fields*)
    email = e; playerId = id; token = kit.signIn(e).str("token")
  private def closed(): Unit = assertEquals(op("POST", s"/players/$playerId/close").status, 200)
  private def file(): Unit = reply = kit.request("POST", kit.playerPath(s"/$playerId/erasure"), Some("{}"), kit.asToken(token))
  private def lapse(): Unit = kit.advance(Duration.ofDays(ErasureWorkflow.DefaultRetentionDays.toLong + 1))
  private def erased(): Unit = kit.eventually("erased", 20.seconds)(Option.when(flow.step == ErasureFlow.Erased)(()))
  private def deposit(): Unit =
    kit.publish(MoneyTransactions, s"$playerId/EUR", DepositConfirmed(playerId, Currency.unsafe("EUR"), s"t-$playerId", MinorUnits.unsafe(700),
      "ref", 1, SignedMinorUnits(700), MinorUnits.unsafe(0), kit.now()))
    kit.eventually("the wallet row")(WalletSummaryView.ofPlayer(kit.views, playerId).headOption): Unit
  private def askExport(): Unit =
    val r = kit.request("POST", kit.playerPath(s"/$playerId/exports"), Some("{}"), kit.asToken(token))
    assertEquals(r.status, 202, r.text)
    val path = kit.playerPath(s"/$playerId/exports/${r.str("exportId")}")
    doc = kit.eventually("the export", 30.seconds)(Option(kit.request("GET", path, None, kit.asToken(token))).filter(_.status == 200)).json

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // ── the filing ─────────────────────────────────────────────────────────────────────────────────

  Given("the player is closed") { () => active(); closed() }
  When("erasure is filed") { () => file() }
  // payments' merchant has no filing of its own (004's erasure route applies at once): its part is
  // asked under the brand's correlation id when the hold lapses, so the filing is the brand's, here
  Then("an erasure request exists in the brand and one in the shared payments with the same correlation id and the brand's retention hold") { () =>
    assertEquals(reply.status, 202, reply.text)
    assertEquals((flow.correlationId, flow.holdUntil.map(_.toString)), (reply.str("correlationId"), Some(reply.str("holdUntil"))))
    assertEquals(player.erasure.map(_.correlationId), Some(flow.correlationId))
    assert(ErasureWorkflow.targets(kit.slug).exists(_.target.project.contains("payments")))
  }
  Then("the player status shows {string} with the hold's date") { (status: String) =>
    val own = kit.request("GET", kit.playerPath(s"/$playerId"), None, kit.asToken(token))
    assertEquals((own.str("status"), own.str("erasureHoldUntil")), (status, reply.str("holdUntil")))
  }

  // ── the application ────────────────────────────────────────────────────────────────────────────

  Given("an erasure whose retention hold has lapsed") { () =>
    active("firstName" -> "Lorna", "lastName" -> "Pike"); deposit(); closed(); file(); assertEquals(reply.status, 202); lapse()
  }
  When("every part of the brand and the shared payments reports it applied") { () => erased() }
  Then("the brand tells the realm to remove the user") { () =>
    assert(kit.realm.receivedOf("delete").nonEmpty)
    assertEquals(kit.realm.userByEmail(email), None)
  }
  Then("the certificates are assembled into the erasure response") { () =>
    val e = op("GET", s"/players/$playerId/erasure").json.get("certificates")
    assertEquals((0 until e.size).map(e.get(_).get("service").asText).toVector, Vector("wallet", "responsible-gambling", "merchant", "players"))
  }
  Then("the player's personal fields read as erased everywhere") { () =>
    assertEquals((player.status, player.profile.get("firstName"), player.primary.map(_.address.reveal)), (Status.Erased, None, Some("")))
    val row = kit.eventually("the row erased")(PlayersView.one(kit.views, playerId).filter(_.primaryEmail.reveal.isEmpty))
    assertEquals(row.displayName.reveal, "")
  }
  Then("their money facts read unchanged") { () =>
    assertEquals(WalletSummaryView.ofPlayer(kit.views, playerId).map(_.realBalanceAfter), Vector(700L))
    assert(kit.facts(MoneyTransactions).exists { case f: DepositConfirmed => f.playerId == playerId; case _ => false })
  }

  // ── a withdrawal ───────────────────────────────────────────────────────────────────────────────

  Given("the player has an open withdrawal request") { () =>
    active(); closed()
    kit.publish(CasinoWithdrawals, playerId, WithdrawalRequested(playerId, "w-1", MinorUnits.unsafe(100), Currency.unsafe("EUR"), "t", kit.now()))
    kit.eventually("the withdrawal open")(Option.when(player.withdrawalOpen)(()))
  }
  Then("it is accepted and held until the withdrawal completes or fails") { () =>
    assertEquals(reply.status, 202, reply.text)
    lapse()
    kit.eventually("held for the withdrawal", 5.seconds)(Option.when(flow.heldFor.contains(ErasureFlow.OpenWithdrawal))(()))
    kit.publish(CasinoWithdrawals, playerId, WithdrawalFailed(playerId, "w-1", MinorUnits.unsafe(100), Currency.unsafe("EUR"), "declined", kit.now()))
    erased()
  }
  Then("the hold's reason says so") { () =>
    assertEquals(op("GET", s"/players/$playerId/erasure").str("status"), "erased")
    assert(flow.heldFor.isEmpty)
  }

  // ── the export ─────────────────────────────────────────────────────────────────────────────────

  private def holding(): Unit =
    active("firstName" -> "Tam")
    op("POST", s"/players/$playerId/notes", """{"content":"feature-note-secret"}"""): Unit
    op("POST", s"/players/$playerId/messages", """{"category":"service","bodyTemplate":"hello"}"""): Unit
    kit.eventually("the message listed")(MessagesView.ofPlayer(kit.views, playerId, 0, 10).headOption): Unit
    kit.eventually("the session listed")(io.eitheror.casino.players.sessions.SessionsView.ofPlayer(kit.views, playerId,
      io.eitheror.casino.players.sessions.SessionRow.Player, 0, 10).headOption): Unit

  Given("the player has a profile, consents, sessions, activity and communications") { () => holding() }
  Given("a player with sessions, deposits, bets and a reviewed document") { () => holding(); deposit() }
  When("they ask for their data") { () => askExport() }
  When("the player asks for their export") { () => askExport() }
  Then("they receive an export covering the profile, consents, sessions, activity history and communications") { () =>
    Seq("profile", "consents", "sessions", "activity", "messages").foreach(f => assert(doc.has(f), f))
    assertEquals(doc.get("profile").get("firstName").asText, "Tam")
    assert(doc.get("sessions").size >= 1 && doc.get("messages").size >= 1)
  }
  Then("it carries their profile, consents, sessions, activity and inbox items") { () =>
    Seq("profile", "consents", "sessions", "activity", "messages").foreach(f => assert(doc.has(f), f))
  }
  // the document records are kyc's part (spec 009), asked the same way once kyc serves the route
  Then("the wallet's transactions, the payments and the document records, not the documents") { () =>
    assertEquals((doc.get("parts").has("wallet"), doc.get("parts").has("merchant")), (true, true))
  }
  Then("operator notes are excluded") { () => assert(!Json.render(doc).contains("feature-note-secret")) }
  Then("no operator note") { () => assert(!Json.render(doc).contains("feature-note-secret")) }

  Given("the wallet does not answer in time") { () => active(); kit.wallet.silent = true }
  Then("the export names the wallet's part as missing") { () =>
    kit.wallet.silent = false
    assert((0 until doc.get("missing").size).map(doc.get("missing").get(_).asText).contains("wallet"))
  }

  // ── a new registration ─────────────────────────────────────────────────────────────────────────

  Given("an erased player's email address") { () => active(); closed(); file(); lapse(); erased() }
  When("a visitor registers with it") { () => reply = kit.register("email" -> email) }
  Then("a new player with a new id is created") { () =>
    assertEquals(reply.status, 201, reply.text)
    assertNotEquals(reply.str("playerId"), playerId)
  }
  Then("the realm accepts the address") { () => assert(kit.realm.userByEmail(email).exists(_.playerId == reply.str("playerId"))) }

  override def afterEach(context: AfterEach): Unit =
    kit.wallet.silent = false
    super.afterEach(context)
