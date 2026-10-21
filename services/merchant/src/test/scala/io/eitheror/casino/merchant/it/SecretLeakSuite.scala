package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.Json

import java.sql.DriverManager
import scala.concurrent.duration.*

/**
 * SC-007: no secret appears in any event, view row or published fact. After a credential with secrets,
 * a terminal with secret properties, a signed capture and an operator's invitation, every table of the
 * service's database but the secret store's own, and every message on the in-memory broker, is scanned
 * for each secret and token written. The secret store holds them encrypted, so even its table must not
 * show one in the clear.
 */
class SecretLeakSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  private var kit: Kit = null
  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  test("no secret or token is in the journal, the views, the timers, the published facts, or the store in the clear") {
    val webhook  = "whsec-LEAKCHECK-1"
    val credKey  = "sk-LEAKCHECK-2"
    val termKey  = "tk-LEAKCHECK-3"
    val op       = kit.asOperator()
    val r        = kit.subBrand(brand = Some("goldvibe"), webhookSecret = Some(webhook))
    assertEquals(kit.request("PUT", s"/v1/merchants/${r.subBrandId}/psp-credentials/DREAMZPAY?name=main",
      Some(s"""{"credentials":{"secureKey":"$credKey","webhookSecret":"$webhook"}}"""), op).status, 200)
    val t = kit.request("POST", s"/v1/merchants/${r.subBrandId}/gateways/DREAMZPAY/integrations",
      Some(s"""{"scheme":"VISA","name":"V","regions":[],"properties":{"memberId":"m","secureKey":"$termKey"}}"""), op)
    assertEquals(t.status, 201, t.text)
    val key = kit.request("POST", s"/v1/merchants/${r.subBrandId}/keys", Some("""{"name":"f"}"""), op).str("rawKey")
    val id = kit.payment(r)
    val body = """{"outcome":"success"}"""
    assertEquals(kit.capture(id, body, kit.signed(body, webhook)).status, 200)
    val created = kit.request("POST", "/v1/operators", Some(Json.render(Json.obj("email" -> "leak@acme.example", "role" -> "resource_viewer",
      "scopeLevel" -> "company", "scopeId" -> r.companyId))), op)
    assertEquals(created.status, 201, created.text)
    val invitationToken = kit.invitations.to("leak@acme.example").head.token
    Thread.sleep(3000) // the views and consumers catch up

    val secrets = Vector(webhook, credKey, termKey, key, invitationToken)
    val conn = DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val tables = Iterator.unfold(conn.getMetaData.getTables(null, null, "%", Array("TABLE")))(rs => Option.when(rs.next())((rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME")), rs)).toVector
        .filterNot((schema, _) => schema == "pg_catalog" || schema == "information_schema")
      assert(tables.nonEmpty)
      val leaks = for
        (schema, table) <- tables
        rs = conn.createStatement().executeQuery(s"""SELECT * FROM "$schema"."$table"""")
        row <- Iterator.unfold(rs)(rs => Option.when(rs.next())((1 to rs.getMetaData.getColumnCount).map(i =>
          Option(rs.getBytes(i)).map(b => String(b, java.nio.charset.StandardCharsets.UTF_8)).getOrElse("")).mkString("|"), rs)).toVector
        s <- secrets if row.contains(s)
      yield s"$schema.$table holds ${s.take(8)}…"
      assertEquals(leaks, Vector.empty)
      // The scan can fail: a value that is stored, the credential's id, is found by the same reading.
      val control = tables.exists { (schema, table) =>
        val rs = conn.createStatement().executeQuery(s"""SELECT * FROM "$schema"."$table"""")
        Iterator.unfold(rs)(rs => Option.when(rs.next())((1 to rs.getMetaData.getColumnCount).exists(i =>
          Option(rs.getBytes(i)).exists(b => String(b, java.nio.charset.StandardCharsets.UTF_8).contains(r.credential.id))), rs)).contains(true)
      }
      assert(control, "the scan found nothing it should have found")
    finally conn.close()

    val published = kit.broker.publishedTo(io.eitheror.casino.contract.facts.PaymentsMerchants.name).toVector ++
      kit.broker.publishedTo(io.eitheror.casino.contract.facts.PlatformAudit.name).toVector
    assert(published.nonEmpty, "the facts were published")
    val inFacts = published.map(d => String(d.message.payload, java.nio.charset.StandardCharsets.UTF_8)).flatMap(text => secrets.filter(text.contains))
    assertEquals(inFacts, Vector.empty)
  }
