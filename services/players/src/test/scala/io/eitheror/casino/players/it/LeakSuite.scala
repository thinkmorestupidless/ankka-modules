package io.eitheror.casino.players.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.PersonalFields
import io.eitheror.casino.players.Json

import java.util.UUID
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
