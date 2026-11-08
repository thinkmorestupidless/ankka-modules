package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.accounts.{Affiliate, Erasure, ErasureRules, Secrets}
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.links.Clicks
import io.eitheror.casino.programme.players.{ClickRecord, PlayerAttribution}
import io.eitheror.casino.programme.sealing.Sealing

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{DriverManager, Types}
import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*

/**
 * SC-007, FR-037 (T080): an affiliate's name, email, company, website, traffic source and payment details
 * rest nowhere readable. They go in through the application, the affiliate's own patch, a manager's
 * patch and a change of email; every table of the service's database, journals and views included, is
 * then searched for each. After the erasure the data key is gone and the sealed identity cannot be
 * opened. A service of its own (build.sbt), so its database holds this suite's work alone.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: scala.concurrent.duration.Duration = 10.minutes

  private lazy val kit: Kit = Kit()

  override def beforeAll(): Unit = kit: Unit
  override def afterAll(): Unit  = kit.stop()

  val markers: Vector[(String, String)] = Vector(
    "name"           -> "Zebedee Quillfeather",
    "display name"   -> "ZebQuill",
    "email"          -> "zebedee.quillfeather@leak.example",
    "second email"   -> "z.quillfeather.two@leak.example",
    "company"        -> "Quillfeather Ventures Ltd",
    "website"        -> "quillfeather-leak.example",
    "traffic source" -> "QuillfeatherTraffic",
    "IBAN"           -> "GB82WEST12345698765432"
  )
  private def marker(kind: String) = markers.toMap.apply(kind)

  /** Every row of every table in the service's database, as the table and the row's values joined; `bytea` read as UTF-8. */
  private def database(): Vector[(String, String)] =
    val c = DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val tables = Iterator.unfold(c.getMetaData.getTables(null, null, "%", Array("TABLE"))) { rs =>
        Option.when(rs.next())((rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"), rs))
      }.toVector.filterNot(t => t.startsWith("pg_catalog.") || t.startsWith("information_schema."))
      tables.flatMap { table =>
        val rs   = c.createStatement().executeQuery(s"""SELECT * FROM ${table.split('.').map(p => s""""$p"""").mkString(".")}""")
        val meta = rs.getMetaData
        val out  = Vector.newBuilder[(String, String)]
        while rs.next() do
          val values = (1 to meta.getColumnCount).flatMap { i =>
            meta.getColumnType(i) match
              case Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Option(rs.getBytes(i)).map(String(_, UTF_8))
              case _                                                     => Option(rs.getString(i))
          }
          out += table -> values.mkString(" | ")
        out.result()
      }
    finally c.close()

  /** Where each value rests: a table and the row, cut short. Case is ignored, as an email is stored lowercased. */
  private def found(values: Vector[String]): Vector[String] =
    val db = database()
    values.flatMap(v => db.collect { case (table, row) if row.toLowerCase.contains(v.toLowerCase) => s"$v in $table: ${row.take(300)}" })

  test("an affiliate's identity and payment details rest nowhere readable, and an erasure leaves the sealed value unopenable") {
    val email = marker("email")
    val applied = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(
      s"""{"email":"$email","name":"${marker("name")}","companyName":"${marker("company")}","website":"${marker("website")}",
         |"trafficSource":"${marker("traffic source")}"}""".stripMargin), kit.fromInternet)
    assertEquals(applied.status, 201, applied.text)
    val aff  = applied.json.get("affiliateId").asText
    val mgr  = kit.asManager()
    val plan = kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 1_000)
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/approve", Some(s"""{"planId":"$plan"}"""), mgr).status, 200)
    val own = kit.request("PATCH", s"/v1/my/$aff/account", Some(s"""{"paymentMethod":"IBAN ${marker("IBAN")}","displayName":"${marker("display name")}"}"""),
      kit.asAffiliate(aff))
    assertEquals(own.status, 200, own.text)
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/update-email", Some(s"""{"email":"${marker("second email")}"}"""), mgr)
      .status, 200)
    val read = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff", headers = mgr)
    assertEquals((read.json.get("email").asText, read.json.get("paymentMethod").asText), (marker("second email"), s"IBAN ${marker("IBAN")}"))
    kit.eventually("the realm user")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser): Unit
    Thread.sleep(1500) // the views' last poll

    val leaks = found(markers.map(_._2) ++ Vector("Zebedee", "Quillfeather"))
    assert(leaks.isEmpty, leaks.mkString("\n"))

    // The erasure: closed, filed, held to the retention's end, then the key is gone.
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/close", headers = mgr).status, 200)
    val filed = kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/erasure", headers = mgr)
    assertEquals(filed.status, 202, filed.text)
    val sealedBefore = kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().identity
    val keyBefore    = Secrets(kit.secrets).existingDataKey(aff).toOption.flatten.getOrElse(fail("no data key before the erasure"))
    assert(Sealing.open(sealedBefore, keyBefore, aff).isRight)
    val hold = ErasureRules.holdUntil(kit.now, 1825)
    assertEquals(filed.json.get("holdUntil").asText, hold.toString)
    kit.moveClock(hold.plus(Duration.ofMinutes(1)))
    kit.eventually("erased", 60.seconds)(
      Option(kit.components.forWorkflow(EntityId(aff)).call(Erasure.status).invoke()).filter(_.status == ErasureRules.Erased)): Unit
    Secrets(kit.secrets).invalidate()
    // The key that opened it is the store's no longer: the sealed value stays, and nothing opens it.
    assertEquals(Secrets(kit.secrets).existingDataKey(aff).map(_.isDefined), Right(false))
    assertEquals(kit.realm.byEmail(marker("second email")), None)
    val after = kit.request("GET", s"/v1/affiliates/goldvibe/accounts/$aff", headers = mgr)
    assertEquals((after.json.get("email").asText, after.json.get("name").asText), ("erased", "erased"))
    val again = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"${marker("second email")}"}"""), kit.fromInternet)
    assertEquals(again.status, 201, again.text)
    assertNotEquals(again.json.get("affiliateId").asText, aff)
  }

  test("an erasure the realm refuses every time erases nothing and is recorded failed; filed again, it erases") {
    val applied = kit.request("POST", "/v1/affiliates/goldvibe/apply", Some(s"""{"email":"${kit.fresh("refused")}@example.com"}"""), kit.fromInternet)
    val aff     = applied.json.get("affiliateId").asText
    val mgr     = kit.asManager()
    val plan    = kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 1_000)
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/approve", Some(s"""{"planId":"$plan"}"""), mgr).status, 200)
    kit.eventually("the realm user")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser): Unit
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/close", headers = mgr).status, 200)
    kit.realm.failing = Some("the realm answers 500")
    try
      val filed = kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/erasure", headers = mgr)
      assertEquals(filed.status, 202, filed.text)
      kit.moveClock(ErasureRules.holdUntil(kit.now, 1825).plus(Duration.ofMinutes(1)))
      kit.eventually("failed", 120.seconds)(
        Option(kit.components.forWorkflow(EntityId(aff)).call(Erasure.status).invoke()).filter(_.status == ErasureRules.Failed)): Unit
      Secrets(kit.secrets).invalidate()
      assertEquals(Secrets(kit.secrets).existingDataKey(aff).map(_.isDefined), Right(true))
      assertEquals(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().erased, None)
    finally kit.realm.failing = None
    // Its hold already served: filed again, it goes straight to erasing.
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/erasure", headers = mgr).status, 202)
    kit.eventually("erased", 60.seconds)(
      Option(kit.components.forWorkflow(EntityId(aff)).call(Erasure.status).invoke()).filter(_.status == ErasureRules.Erased)): Unit
  }

  test("FR-038: a closed player's click id and its click's details rest nowhere but the journal, its postback's address dropped once sent") {
    val clickId = s"click-${UUID.randomUUID()}"
    val aff     = kit.affiliate(kit.fresh("aff"), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 1_000))
    Messaging.postbackTo(kit, aff)
    val facts  = Facts("goldvibe", aff, kit.link(kit.fresh("lnk"), aff, "goldvibe"), clickId = Some(clickId))
    val player = kit.fresh("ps")
    val click  = facts.click(kit.now)
    kit.publish(click)
    kit.eventually("the click")(Option(kit.components.forKeyValueEntity(ClickRecord.entityId("goldvibe", click.factId)).call(ClickRecord.get)
      .invoke()).filter(_.clickId.contains(clickId))): Unit
    kit.publish(facts.registration(player, kit.now))
    kit.publish(facts.firstDeposit(player, 5_000, kit.now))
    kit.eventually("the postback, with the click id")(kit.postbacks.calls.find(_.query.get("click").contains(clickId))): Unit
    kit.publish(facts.playerClosed(player, kit.now))
    kit.eventually("closed")(Option(kit.components.forEventSourcedEntity(PlayerAttribution.entityId("goldvibe", player))
      .call(PlayerAttribution.get).invoke()).filter(_.closed.isDefined)): Unit
    kit.eventually("the click forgotten")(Option(kit.components.forKeyValueEntity(ClickRecord.entityId("goldvibe", click.factId))
      .call(ClickRecord.get).invoke()).filter(c => c.clickId.isEmpty && c.country.isEmpty)): Unit
    kit.eventually("the click's row forgotten")(Option(kit.views.forView(Clicks).ask(Clicks.withClickId, "brand" -> "goldvibe",
      "click_id" -> clickId)).filter(_.isEmpty)): Unit
    // Bounded by the waits above, which the same handlers wrote first: this covers the views' last poll.
    Thread.sleep(1500)
    // The journal keeps the events the player's state is made from; their retention waits for ankka 042 (research).
    val leaks = found(Vector(clickId)).filterNot(_.contains("event_journal"))
    assert(leaks.isEmpty, leaks.mkString("\n"))
  }

  test("an invitation's and a reset's token rest nowhere: only its hash, as the token's id (managers' share the step)") {
    val brand = Messaging.brand(kit)
    val email = s"${kit.fresh("tok")}@example.com"
    val aff   = Messaging.apply(kit, brand, email).json.get("affiliateId").asText
    assertEquals(Messaging.approve(kit, brand, aff).status, 200)
    kit.eventually("the realm user")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser): Unit
    val mgr = kit.asManager(Seq(brand))
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/reset-password", headers = mgr).status, 204)
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/resend-invitation", headers = mgr).status, 204)
    val links  = Seq("AffiliatePasswordReset", "AffiliateInvite").map(k => Messaging.emailed(kit, email, k).data("link"))
    val tokens = links.map(_.split("token=").last)
    assertEquals(tokens.count(_.length >= 40), 2, links.mkString(", "))
    Thread.sleep(1500) // the views' last poll
    val leaks = found(tokens.toVector)
    assert(leaks.isEmpty, leaks.mkString("\n"))
  }

  test("the search finds a value planted in a table (positive control)") {
    val planted = s"planted-${UUID.randomUUID()}"
    val aff     = kit.affiliate(kit.fresh("aff"), "goldvibe", kit.plan("goldvibe", kit.fresh("p")))
    assertEquals(kit.request("POST", s"/v1/affiliates/goldvibe/accounts/$aff/notes", Some(s"""{"text":"$planted"}"""), kit.asManager()).status, 201)
    kit.eventually("planted")(Option(found(Vector(planted))).filter(_.nonEmpty)): Unit
  }
