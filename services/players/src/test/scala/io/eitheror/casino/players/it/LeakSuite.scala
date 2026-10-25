package io.eitheror.casino.players.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.PersonalFields
import io.eitheror.casino.players.Json

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-006, spec 001 FR-023: where a player's personal values may and may not be read. Every marker is a
 * value no test would write by chance, so finding one is a leak. This is the frame and the registration
 * case (T027); T069 adds the erasure (every table, view row, fact, operator answer and the export after
 * it) and T072 the deposit's customer fields.
 */
class LeakSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  /** One run's markers, each unique to the run. */
  final case class Markers(run: String = UUID.randomUUID().toString.take(8)):
    val displayName = s"Zebedee$run"
    val givenName   = s"Quilliam$run"
    val familyName  = s"Vandersnoot$run"
    val dateOfBirth = "1971-03-17"
    val address     = s"leak-$run@example.test"
    val telephone   = s"+4479$run".filter(c => c.isDigit || c == '+').take(13)
    val bankAccount = s"GB29LEAK$run"
    val network     = "203.0.113.77"
    val userAgent   = s"LeakAgent/$run"
    val fingerprint = s"fp-leak-$run"
    val password    = s"Leak-Passw0rd-$run"
    val noteText    = s"a note that must not leak $run"
    val messageBody = s"a message body that must not leak $run"

    /** The values that are personal, not counting the password, which is never stored anywhere. */
    def personal: Vector[String] =
      Vector(displayName, givenName, familyName, address, telephone, bankAccount, network, userAgent, fingerprint, noteText, messageBody)

    def all: Vector[String] = personal :+ password

  /** Where to look, by name, and what each holds as text. */
  type Places = Vector[(String, Vector[String])]

  def assertNowhere(markers: Seq[String], places: Places): Unit =
    val found = for
      (place, texts) <- places
      text           <- texts
      marker         <- markers
      if text.contains(marker)
    yield s"$marker in $place: ${text.take(200)}"
    assert(found.isEmpty, found.distinct.mkString("\n"))

  /** Every topic `players` reads or writes. */
  val topics: Vector[Topic[?]] = Vector(CasinoPlayers, CasinoSessions, PlatformAudit, MoneyTransactions, CasinoRounds,
    CasinoRewards, CasinoWithdrawals, CasinoPush)

  /** A fact's text with every field FR-023 names as personal removed: what is left may hold no marker. */
  def outsidePersonal(text: String): String =
    def snake(name: String) = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase
    def strip(node: JsonNode): JsonNode =
      if node.isObject then
        val copy = Json.obj()
        node.fieldNames.asScala.foreach { name =>
          if !PersonalFields.names(snake(name)) then copy.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].set(name, strip(node.get(name))): Unit
        }
        copy
      else if node.isArray then Json.arr(node.elements.asScala.toVector.map(strip)*)
      else node
    Json.parse(text).map(n => Json.render(strip(n))).getOrElse(text)

  def facts: Places      = topics.filterNot(_ == PlatformAudit).map(t => s"facts on ${t.name} (outside personal fields)" -> kit.factTexts(t).map(outsidePersonal))
  def audit: Places      = Vector("platform.audit" -> kit.factTexts(PlatformAudit))
  def log: Places        = Vector("the log" -> kit.logText)
  def tables: Places     = kit.tables.toVector.map((t, rows) => s"table $t" -> rows)

  test("the frame finds a marker where it is, and names the place") {
    val m = Markers()
    val e = intercept[AssertionError](assertNowhere(m.personal, Vector("somewhere" -> Vector(s"x ${m.address} y"))))
    assert(e.getMessage.contains("somewhere"), e.getMessage)
    assertEquals(outsidePersonal(s"""{"playerId":"p","email":"${m.address}","displayName":"${m.displayName}"}"""), """{"playerId":"p"}""")
  }

  test("a registration with every marker: the password and the body nowhere in the log; no marker outside a personal field of a fact, nor in the audit") {
    val m = Markers()
    val r = kit.register("email" -> m.address, "password" -> m.password, "displayName" -> m.displayName, "firstName" -> m.givenName,
      "lastName" -> m.familyName, "dateOfBirth" -> m.dateOfBirth, "deviceFingerprint" -> m.fingerprint)
    assert(Set(201, 501).contains(r.status), r.text)
    assertNowhere(m.all, log)
    assertNowhere(m.all, facts)
    assertNowhere(m.all, audit)
    assertNowhere(Seq(m.password), tables)
  }

  /** The tables the named gap covers until ankka 042 (research R8): the journal's earlier events and their snapshots. */
  val JournalGap: Set[String] = Set("event_journal", "snapshot")

  test("T069: before erasure no marker in the log or the audit; after it none in any table outside the journal, view row, later fact, operator answer") {
    val m = Markers()
    val r = kit.register("email" -> m.address, "password" -> m.password, "displayName" -> m.displayName, "firstName" -> m.givenName,
      "lastName" -> m.familyName, "dateOfBirth" -> m.dateOfBirth, "deviceFingerprint" -> m.fingerprint)
    assertEquals(r.status, 201, r.text)
    val id = r.str("playerId")
    assertEquals(kit.verify(m.address).status, 200)
    val signIn = kit.json("POST", kit.playerPath("/login"), Json.obj("email" -> m.address, "password" -> m.password),
      kit.asVisitor ++ Seq("X-Forwarded-For" -> m.network, "User-Agent" -> m.userAgent))
    assertEquals(signIn.status, 200, signIn.text)
    val token = signIn.str("token")
    assertEquals(kit.request("PATCH", kit.playerPath(s"/$id"), Some(Json.render(Json.obj("phone" -> m.telephone, "bankAccount" -> m.bankAccount))),
      kit.asToken(token)).status, 200)
    def op(method: String, path: String, body: String = "{}") = kit.request(method, kit.operatorPath(path), Some(body), kit.asOperator())
    assertEquals(op("POST", s"/players/$id/notes", Json.render(Json.obj("content" -> m.noteText))).status, 201)
    assertEquals(op("POST", s"/players/$id/messages", Json.render(Json.obj("category" -> "service", "bodyTemplate" -> m.messageBody))).status, 201)
    kit.eventually("the message listed")(io.eitheror.casino.players.inbox.MessagesView.ofPlayer(kit.views, id, 0, 10).headOption)
    Thread.sleep(1000)
    assertNowhere(m.all, log)
    assertNowhere(m.all, audit)

    assertEquals(op("POST", s"/players/$id/close").status, 200)
    assertEquals(op("POST", s"/players/$id/erasure").status, 202)
    val factsBefore = topics.map(t => t.name -> kit.factTexts(t).size).toMap
    kit.advance(java.time.Duration.ofDays(io.eitheror.casino.players.erasure.ErasureWorkflow.DefaultRetentionDays.toLong + 1))
    kit.eventually("erased", 20.seconds)(Option.when(
      io.eitheror.casino.players.erasure.ErasureWorkflow.stateOf(kit.components, id).step == io.eitheror.casino.players.erasure.ErasureFlow.Erased)(()))
    kit.eventually("the views erased")(io.eitheror.casino.players.player.PlayersView.one(kit.views, id).filter(_.primaryEmail.reveal.isEmpty))
    Thread.sleep(1500)

    val answers: Places = Vector("profile", "crm", "messages", "activity", "sessions", "wallet").map(r =>
      s"operator $r" -> Vector(kit.request("GET", kit.operatorPath(s"/players/$id/$r"), None, kit.asOperator()).text))
    val later: Places = topics.map(t => s"facts on ${t.name} after the erasure" -> kit.factTexts(t).drop(factsBefore(t.name)))
    val (gap, rest) = tables.partition((place, _) => JournalGap.exists(t => place == s"table $t"))
    assertNowhere(m.personal, answers ++ later)
    // a bytea column (durable_state's payload) reads as hex in a row's text, so the tables are searched both ways
    assertNowhere(m.personal ++ m.personal.map(v => java.util.HexFormat.of().formatHex(v.getBytes("UTF-8"))), rest)
    // the named gap (R8): the journal still holds the earlier events; this flips when ankka 042 lands
    // the payload is bytea, which a row's text shows as hex
    val hex = java.util.HexFormat.of().formatHex(m.givenName.getBytes("UTF-8"))
    assert(gap.exists(_._2.exists(_.contains(hex))), "the journal no longer holds the earlier events: ankka 042 has landed; flip this")
  }

