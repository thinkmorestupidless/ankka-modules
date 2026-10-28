package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.tag.Pseudonyms

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The operator's routes (T069; contracts/routes.md *Operator*): a staff token naming the brand reads and
 * changes each of them; one naming another brand is refused `brand_not_in_roles`, a player's token
 * `wrong_issuer`. No answer holds a pseudonym key, a signing key's private half or a webhook secret, and the
 * status has every section with the counts this suite's state implies. A kit of its own, so the counts are
 * this suite's alone.
 */
class OperatorSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private val second = FakeNetwork("second")
  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k

  override def afterAll(): Unit = { kit.stop(); second.stop() }

  private val Backoffice = s"/v1/casino/backoffice/goldvibe/attribution"
  private val Webhooks   = s"/v1/casino/backoffice/goldvibe/attribution-webhooks"
  private val answers    = mutable.ArrayBuffer.empty[String]

  private def as(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = kit.asOperator()): Reply =
    val r = kit.request(method, path, body, headers)
    answers += r.text
    r

  /** Every operator route of routes.md, with a body its handler reads. */
  private def routes(sub: String): Vector[(String, String, Option[String])] = Vector(
    ("GET", s"$Backoffice/settings", None),
    ("PUT", s"$Backoffice/settings", Some("""{"attributionWindowDays":30,"bonusCodeWindowDays":30}""")),
    ("GET", s"$Backoffice/status", None),
    ("GET", s"$Backoffice/networks", None),
    ("POST", s"$Backoffice/networks", Some("{}")),
    ("GET", s"$Backoffice/networks/programme", None),
    ("PUT", s"$Backoffice/networks/programme", Some("{}")),
    ("DELETE", s"$Backoffice/networks/none", None),
    ("POST", s"$Backoffice/networks/programme/pseudonym-key", None),
    ("GET", s"$Backoffice/records/p-none", None),
    ("GET", s"$Backoffice/signing-keys", None),
    ("POST", s"$Backoffice/signing-keys", None),
    ("DELETE", s"$Backoffice/signing-keys/none", None),
    ("GET", Webhooks, None),
    ("POST", Webhooks, Some("{}")),
    ("GET", s"$Webhooks/undelivered?network=programme", None),
    ("POST", s"$Webhooks/undelivered/${URLEncoder.encode(s"$sub/click/c-none", UTF_8)}/resend", None),
    ("POST", s"$Webhooks/undelivered/${URLEncoder.encode(s"$sub/click/c-none", UTF_8)}/discard", None),
    ("GET", s"$Webhooks/$sub", None),
    ("PUT", s"$Webhooks/$sub", Some("""{"active":true}""")),
    ("DELETE", s"$Webhooks/none", None)
  )

  test("a staff token with the brand reads and changes every operator route; one with another brand, or a player's, is refused") {
    val sub = as("POST", Webhooks, Some(Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl,
      "secret" -> "whsec-operator-suite")))).json.get("id").asText
    for (method, path, body) <- routes(sub) do
      val ours = as(method, path, body)
      assert(!Set(401, 403, 405).contains(ours.status), s"$method $path answered ${ours.status}: ${ours.text}")
      val other = kit.request(method, path, body, kit.asOperator(roles = "brand_other"))
      assertEquals(other.status, 403, s"$method $path under brand_other")
      assert(other.text.contains("brand_not_in_roles"), s"$method $path: ${other.text}")
      val player = kit.request(method, path, body, kit.asPlayer())
      assertEquals(player.status, 403, s"$method $path under a player")
      assert(player.text.contains("wrong_issuer"), s"$method $path: ${player.text}")
    // A platform administrator is an operator of every brand.
    assertEquals(kit.request("GET", s"$Backoffice/settings", headers = kit.asOperator(roles = "platform_admin")).status, 200)
  }

  test("networks are added, changed and removed; the pseudonym key is made once and no answer holds a key or a secret") {
    val added = as("POST", s"$Backoffice/networks", Some(Json.render(Json.obj("id" -> "second", "keyDocument" -> second.keyDocumentUrl,
      "revocationList" -> second.revocationsUrl, "machine" -> "acme/second"))))
    assertEquals(added.status, 201, added.text)
    assertEquals((added.json.get("machine").asText, added.json.get("pseudonymKeyMade").asBoolean), ("acme/second", false))
    assertEquals(as("POST", s"$Backoffice/networks", Some("""{"id":"third","revocationList":"http://x"}""")).json.get("field").asText,
      "keyDocument")

    val made = as("POST", s"$Backoffice/networks/second/pseudonym-key")
    assertEquals(made.status, 201, made.text)
    assertEquals(made.json, Json.parse("""{"network":"second","made":true}""").get)
    assertEquals(as("POST", s"$Backoffice/networks/second/pseudonym-key").status, 409)

    val changed = as("PUT", s"$Backoffice/networks/second", Some("""{"roundStream":true,"machine":"machine:acme/second"}"""))
    assertEquals((changed.status, changed.json.get("roundStream").asBoolean, changed.json.get("pseudonymKeyMade").asBoolean), (200, true, true))
    assertEquals(changed.json.get("machine").asText, "acme/second", "the CLI's grantee form is held as <organization>/<name>")
    assertEquals(as("GET", s"$Backoffice/networks/second").json.get("keyDocument").asText, second.keyDocumentUrl)
    kit.eventually("both listed")(Option.when(as("GET", s"$Backoffice/networks").json.get("networks").size == 2)(())): Unit
    val removed = as("DELETE", s"$Backoffice/networks/second")
    assertEquals(removed.status, 200, removed.text)
    assert(removed.json.hasNonNull("removed"), removed.text)
    assertEquals(as("GET", s"$Backoffice/networks/nowhere").status, 404)

    val k1 = as("POST", s"$Backoffice/signing-keys")
    val k2 = as("POST", s"$Backoffice/signing-keys")
    assertEquals((k1.status, k2.status), (201, 201))
    // Every live key but k2 retired (the route sweep made one too); then k2 is the last, and stays.
    val live = as("GET", s"$Backoffice/signing-keys").json.get("keys").elements.asScala.toVector
      .filter(k => !k.hasNonNull("retiredAt")).map(_.get("kid").asText).filterNot(_ == k2.json.get("kid").asText)
    assert(live.contains(k1.json.get("kid").asText))
    live.foreach(kid => assertEquals(as("DELETE", s"$Backoffice/signing-keys/$kid").status, 200))
    assertEquals(as("DELETE", s"$Backoffice/signing-keys/${k2.json.get("kid").asText}").status, 409, "the last live key stays")

    val sub = as("POST", s"$Webhooks", Some(Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl,
      "secret" -> "whsec-never-shown"))))
    assertEquals(sub.status, 201, sub.text)
    as("GET", s"$Webhooks/${sub.json.get("id").asText}"): Unit
    as("GET", s"$Webhooks?network=programme"): Unit

    // No answer holds a pseudonym key, a signing key's private half or a webhook's secret.
    val secrets = Vector("programme", "second").flatMap(n => kit.testKit.secrets.get(Pseudonyms.secretName(n))) ++
      Vector(k1, k2).flatMap(k => kit.testKit.secrets.get(s"signing-key/${k.json.get("kid").asText}")) ++
      Vector("whsec-never-shown", "whsec-operator-suite")
    assertEquals(secrets.size, 6)
    val held = for s <- secrets; a <- answers if a.contains(s) yield s"${s.take(6)}… in ${a.take(200)}"
    assertEquals(held, Vector.empty)
  }

  test("the status has every section with the counts the suite's state implies") {
    val players = Vector.fill(3)(kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk")))))
    players.foreach(p => kit.eventually("bound")(kit.record(p).binding): Unit)
    val days   = DayClock(kit)
    val day    = days.evening()
    val wallet = WalletScript(kit, players.head)
    wallet.deposit(1000)
    wallet.bet("r-1", 100)
    days.played(wallet, day)

    val status = kit.eventually("the counts") {
      val s = as("GET", s"$Backoffice/status").json
      // The open day comes from a view of its own: waited for too, or the count below races it.
      Option(s).filter(s => s.get("networks").elements.asScala.exists(n => n.get("id").asText == "programme" && n.get("boundPlayers").asLong == 3)
        && s.get("openDays").asLong == 1L)
    }
    assertEquals(status.fieldNames.asScala.toSet, Set("networks", "transition", "openDays", "legacyPerDay"))
    val programme = status.get("networks").elements.asScala.find(_.get("id").asText == "programme").get
    assertEquals((programme.get("reachable").asBoolean, programme.get("undelivered").asLong), (true, 0L))
    assert(programme.hasNonNull("lastFetch"), programme.toString)
    // A removed network binds no one new, and nothing else changes (FR-025): it is still watched, never reachable.
    val removed = status.get("networks").elements.asScala.find(_.get("id").asText == "second").getOrElse(fail("second not listed"))
    assertEquals((removed.get("reachable").asBoolean, removed.get("boundPlayers").asLong), (false, 0L))
    assertEquals(status.get("openDays").asLong, 1L)
    val t = status.get("transition")
    assertEquals((t.get("firstDay").asText, t.get("legacyTagsLast30Days").asLong), ("2026-10-25", 0L))
    assert(t.hasNonNull("endsAt") && !t.hasNonNull("endedOn"), t.toString)

    val record = as("GET", s"$Backoffice/records/${players.head}")
    assertEquals(record.status, 200, record.text)
    assertEquals(record.json.get("binding").get("affiliate").asText, "aff-1")
    assertEquals(record.json.get("firstDepositAt"), null)
  }
