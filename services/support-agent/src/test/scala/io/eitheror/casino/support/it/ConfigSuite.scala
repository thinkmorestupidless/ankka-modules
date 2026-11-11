package io.eitheror.casino.support.it

import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.config.ConfigBody

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
