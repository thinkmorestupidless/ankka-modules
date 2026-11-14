package io.eitheror.casino.support.it

import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.config.{AgentConfiguration, ConfigBody, ConfigChange, ConfigVersion}

import scala.concurrent.duration.*

/**
 * The operator's configuration routes (T049, T051; FR-029, FR-023, contracts/routes.md): read, change and version
 * the brand's configuration under its operators' roles, with each refusal the table names.
 */
class ConfigSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def path(rest: String = "") = s"/v1/casino/agent-config/${kit.slug}$rest"
  private def get(rest: String = "", roles: String = "brand_admin") = kit.request("GET", path(rest), headers = kit.asOperator(roles))
  private def put(body: String, roles: String = "brand_admin") = kit.request("PUT", path("/"), Some(body), kit.asOperator(roles))
  private def version = Json.parse(get("/").text).flatMap(_.long("version")).getOrElse(-1L)

  test("GET / answers the version, its body and the changes kept"):
    val r = get("/")
    assertEquals(r.status, 200)
    val b = Json.parse(r.text).get
    assertEquals(b.get("config").get("turnsPerMinute").asInt, Kit.TurnsPerMinute)
    assert(b.get("recentChanges").size > 0, r.text)

  test("PUT / changes what it names, keeps the rest, and answers the next version; that version reads back"):
    val before = version
    val r      = put("""{"supportHours": "every day, 09:00–21:00"}""")
    assertEquals((r.status, Json.parse(r.text).flatMap(_.long("version"))), (200, Some(before + 1)))
    val shown = Json.parse(get("/").text).get.get("config")
    assertEquals((shown.get("supportHours").asText, shown.get("enabled").asBoolean), ("every day, 09:00–21:00", true))
    assertEquals(Json.parse(get(s"/versions/${before + 1}").text).get.get("supportHours").asText, "every day, 09:00–21:00")
    assertEquals(Json.parse(get(s"/versions/0").text).get.get("supportHours").asText, ConfigBody().supportHours)

  test("a put that wrote its version but never moved to it is moved to, as its author put it, and the next put goes on over it"):
    val before = version.toInt
    val now    = kit.components.forKeyValueEntity(AgentConfiguration.id(kit.slug)).call(AgentConfiguration.get).invoke()
    val gone   = now.current.copy(supportHours = "weekdays only")
    kit.components.forKeyValueEntity(ConfigVersion.id(kit.slug, before + 1)).call(ConfigVersion.write)
      .invoke(ConfigChange(gone, "op-gone", kit.clock.instant())): Unit
    val r = put("""{"closingMessage": "This conversation is closed."}""")
    assertEquals((r.status, Json.parse(r.text).flatMap(_.long("version"))), (200, Some(before + 2L)))
    val shown = Json.parse(get("/").text).get
    assertEquals((shown.get("config").get("supportHours").asText, shown.get("config").get("closingMessage").asText),
      ("weekdays only", "This conversation is closed."))
    val notes = shown.get("recentChanges")
    assertEquals(notes.get(notes.size - 2).get("by").asText, "op-gone")

  test("a PUT that changes nothing makes no version"):
    val before = version
    assertEquals(Json.parse(put("""{"enabled": true}""").text).flatMap(_.long("version")), Some(before))

  test("a field out of range is 400 invalid_config naming it, an unknown one 400 unknown_field"):
    val bad = put("""{"turnsPerMinute": 0}""")
    assertEquals((bad.status, bad.str("error"), bad.str("field")), (400, "invalid_config", "turnsPerMinute"))
    val unknown = put("""{"colour": "blue"}""")
    assertEquals((unknown.status, unknown.str("error"), unknown.str("field")), (400, "unknown_field", "colour"))

  test("the prompt and the personal fields are the brand admin's; a resource admin changes the rest"):
    val prompt = put("""{"prompt": "Be brief."}""", roles = "resource_admin")
    assertEquals((prompt.status, prompt.str("field")), (403, "prompt"))
    val fields = put("""{"personalFields": ["email"]}""", roles = "resource_admin")
    assertEquals((fields.status, fields.str("field")), (403, "personalFields"))
    assertEquals(put("""{"retentionDays": 30}""", roles = "resource_admin").status, 200)
    assertEquals(put("""{"prompt": "Be brief."}""").status, 200)

  test("a version not yet made is 404"):
    assertEquals(get(s"/versions/${version + 1}").status, 404)
    assertEquals(get("/versions/x").status, 404)

  test("an operator of another brand, or one with no role the routes name, is refused by the ACL"):
    val other = kit.request("GET", path("/"), headers = Seq(kit.asOperator().head,
      "Authorization" -> "Bearer staff|op-2|brand_other,brand_admin"))
    assertEquals(other.status, 403)
    assertEquals(get("/", roles = "support_agent").status, 403)
    assertEquals(kit.request("GET", path("/"), headers = kit.asPlayer("p-cfg")).status, 403)

  // ── T051 ──────────────────────────────────────────────────────────────────

  test("a brand whose agent is off answers 503 agent_unavailable with the unavailable message, and no model is called"):
    assertEquals(put("""{"enabled": false}""").status, 200)
    val s = kit.chat("p-off", "off-1", "hello")
    assertEquals((s.status, s.refusal), (503, "agent_unavailable"))
    assertEquals(Json.parse(s.body).map(_.get("details").get("message").asText), Some(ConfigBody().unavailableMessage))
    assertEquals(kit.model.callCount, 0)

  test("a real-money brand cannot turn the agent on without a safeguarding message and helpline: 422"):
    kit.reset(Kit.Base.copy(realMoney = true))
    kit.configure(_.copy(enabled = false)): Unit
    val refused = put("""{"enabled": true}""")
    assertEquals((refused.status, refused.str("error")), (422, "safeguarding_not_configured"))
    assertEquals(put("""{"enabled": true, "safeguardingMessage": "You are not alone.", "helpline": "0800 000 000"}""").status, 200)
    assertEquals(Json.parse(get("/status").text).map(_.get("safeguarding").asText), Some("on"))

  test("a real-money brand with no judge cannot turn the agent on, and one turned on before answers 503 (FR-023)"):
    kit.reset(Kit.Base.copy(realMoney = true, judge = None))
    kit.configure(_.copy(enabled = false)): Unit
    val refused = put("""{"enabled": true, "safeguardingMessage": "You are not alone.", "helpline": "0800 000 000"}""")
    assertEquals((refused.status, refused.str("error")), (422, "safeguarding_not_configured"))
    kit.configure(_.copy(safeguardingMessage = Some("You are not alone."), helpline = Some("0800 000 000"))): Unit
    val chat = kit.chat("p-c9", "config-nojudge", "hello")
    assertEquals((chat.status, chat.refusal), (503, "agent_unavailable"))
    assertEquals(kit.model.callCount, 0)

  test("a new prompt version governs conversations started after it; one in progress keeps its own"):
    def prompt = kit.model.lastRequest.systemMessage.getOrElse("")
    kit.model.expectText("one")
    assertEquals(kit.chat("p-ver", "ver-old", "hello").names().last, "done")
    assert(prompt.startsWith("You are Motoko"), prompt)
    assertEquals(put("""{"prompt": "You are Motoko, version two."}""").status, 200)
    kit.model.expectText("two")
    assertEquals(kit.chat("p-ver", "ver-new", "hello").names().last, "done")
    assert(prompt.startsWith("You are Motoko, version two."), prompt)
    kit.model.expectText("three")
    assertEquals(kit.chat("p-ver", "ver-old", "again").names().last, "done")
    assert(!prompt.contains("version two"), prompt)
