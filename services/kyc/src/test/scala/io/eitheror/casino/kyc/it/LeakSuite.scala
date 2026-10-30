package io.eitheror.casino.kyc.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.{Personal, PersonalFields}
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.*
import io.eitheror.casino.kyc.record.KycRecord.given

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * FR-032 and FR-033: where a player's personal values may and may not be read. Every marker is a value no
 * test would write by chance, so finding one is a leak. At any time: no marker in a log line, a view row,
 * an audit record, or a fact outside its personal fields. After `Erase`: none in any state or table either,
 * the journal excepted (R15: until ankka 042 the journal keeps earlier events' values, as 008's R8).
 * Spans are not scanned: the integration kit exposes none, and the log lines carry their ids only.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"

  /** One run's markers, each unique to the run. */
  final case class Markers(run: String = UUID.randomUUID().toString.take(8)):
    val givenName   = s"Quilliam$run"
    val familyName  = s"Vandersnoot$run"
    val addressLine = s"$run Leak Lane"
    val dateOfBirth = "1971-03-17"
    val adultFrom   = "1989-03-17"
    val reason      = s"an upload reason that must not leak $run"
    val override_   = s"an override note that must not leak $run"
    val dismissal   = s"a dismissal note that must not leak $run"

    def all: Vector[String] = Vector(givenName, familyName, addressLine, dateOfBirth, adultFrom, reason, override_, dismissal)

  type Places = Vector[(String, Vector[String])]

  private def assertNowhere(markers: Seq[String], places: Places): Unit =
    val found = for
      (place, texts) <- places
      text           <- texts
      marker         <- markers
      if text.contains(marker)
    yield s"$marker in $place: ${text.take(300)}"
    assert(found.isEmpty, found.distinct.mkString("\n"))

  /** A fact's text with every field the contract names as personal removed: what is left may hold no marker. */
  private def outsidePersonal(text: String): String =
    def snake(name: String) = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase
    def strip(node: JsonNode): JsonNode =
      if node.isObject then
        val copy = Json.obj()
        node.fieldNames.asScala.foreach(n => if !PersonalFields.names(snake(n)) then copy.set[JsonNode](n, strip(node.get(n))): Unit)
        copy
      else if node.isArray then Json.arr(node.elements.asScala.toVector.map(strip)*)
      else node
    Json.parse(text).map(n => Json.render(strip(n))).getOrElse(text)

  private def facts: Places = Vector(
    "facts on casino.players (outside personal fields)" -> kit.factTexts(CasinoPlayers).map(outsidePersonal),
    "platform.audit" -> kit.factTexts(PlatformAudit)
  )
  private def log: Places  = Vector("the log" -> kit.logText)
  private def views: Places = kit.tables.toVector.filter(_._1.startsWith("ankka_view_")).map((t, rows) => s"view table $t" -> rows)

  /** The tables the named gap covers until ankka 042 (R15): the journal's earlier events and their snapshots. */
  private val JournalGap: Set[String] = Set("event_journal", "snapshot")

  private def hex(v: String): String = java.util.HexFormat.of().formatHex(v.getBytes("UTF-8"))

  private def record = kit.components.forEventSourcedEntity(EntityId(player))
  private def state  = record.call(KycRecord.get).invoke()

  /** Every path a personal value enters by: the profile fact, an operator's upload, an override, a dismissal. */
  private def life(m: Markers): Unit =
    kit.publish(CasinoPlayers, player, PlayerProfileUpdated(player, Vector("givenName", "dateOfBirth", "addressLine"),
      Some(Personal(m.givenName)), Some(Personal(m.familyName)), Some(Personal(m.dateOfBirth)), Some(Personal("+447900900900")),
      Some(Personal(m.addressLine)), Some(Personal("Leakton")), Some(Personal("LK1 1LK")), Some("MT"), kit.now()))
    kit.eventually("the profile read")(state.profile.adultFrom)
    assertEquals(state.profile.adultFrom.map(_.reveal.toString), Some(m.adultFrom))

    val o = kit.json("POST", kit.operatorPath(s"/$player/uploads"), Json.obj("category" -> "identity_front", "contentType" -> "image/png",
      "reason" -> m.reason), kit.asOperator())
    assertEquals(o.status, 200, o.text)
    assertEquals(kit.putSigned(o.str("url"), Array.fill(32)(5.toByte), "image/png") / 100, 2)
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/documents"), Json.obj("documentId" -> o.str("documentId")), kit.asOperator()).status, 200)

    record.call(KycRecord.countDeposit).invoke(CountDeposit(player, "EUR", 300000, 1, Profiles.Mga, kit.now())): Unit
    assert(state.openFlag(FlagReason.LargeDeposit).isDefined)
    assertEquals(record.call(KycRecord.dismissFlag).invoke(DismissFlag("large_deposit", m.dismissal, "op-1", kit.now())).refusal, None)

    val v = kit.json("POST", kit.operatorPath(s"/$player/override"), Json.obj("status" -> "verified", "reason" -> m.override_), kit.asOperator())
    assertEquals(v.status, 200, v.text)
    kit.eventually("the queue row")(Option.when(kit.tables.getOrElse("ankka_view_kyc_queue", Vector.empty).exists(_.contains(player)))(()))
    kit.eventually("the audit record")(kit.facts(PlatformAudit).collectFirst { case r: AuditRecord if r.subjectId == player && r.action == "kyc.status_changed" => r })

  test("no personal value is in a log line, a view row, an audit record, or a fact outside its personal fields"):
    val m = Markers()
    life(m)
    assertNowhere(m.all, log ++ views ++ facts)

  test("after erasure no personal value is in the state, a view row, an audit record, a fact, a log line, or any table but the journal"):
    val m = Markers()
    life(m)
    val r = kit.request("POST", kit.servicePath(s"/erasure/players/$player"), None, kit.asService("players"))
    assertEquals(r.status, 200, r.text)
    val s = state
    assertEquals(s.profile.adultFrom, None)
    val (gap, rest) = kit.tables.toVector.map((t, rows) => s"table $t" -> rows).partition((place, _) => JournalGap.exists(t => place == s"table $t"))
    val stored      = String(summon[com.thinkmorestupidless.ankka.core.Serializer[KycState]].toBytes(s), "UTF-8")
    assertNowhere(m.all, Vector("the state" -> Vector(stored)) ++ log ++ views ++ facts)
    // a bytea column reads as hex in a row's text, so the tables are searched both ways
    assertNowhere(m.all ++ m.all.map(hex), rest)
    // the named gap (R15): the journal still holds the earlier events; this flips when ankka 042 lands
    assert(gap.exists(_._2.exists(_.contains(hex(m.reason)))), "the journal no longer holds the earlier events: ankka 042 has landed; flip this")
