package io.eitheror.casino.publicapi.features

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.publicapi.contract.{Contract, ContractAmendmentSuite, ContractSchemas}
import io.eitheror.casino.publicapi.it.{Keys, MadeKey, Reply, Seeded}
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions

import scala.jdk.CollectionConverters.*

/** features/public-api/contract.feature (T089): the served contract, a client generated from it, and the drift check. */
class ContractFeatures extends PublicApiFeatures("contract.feature"):
  private val Missing = "9f2c9a52-6f43-4a56-8f0c-0d6d4a0f1a99"
  private var answers: Vector[(String, Reply)] = Vector.empty
  private var served: com.fasterxml.jackson.databind.JsonNode = null
  private lazy val key: MadeKey = Keys.noFields(kit)

  override protected def ranElsewhere: Map[String, String] = Map(
    "a breaking change ships as a new version with the prior one served for at least six months" ->
      ("FR-006 is a release rule with one version to date: the drift check fails any change to /v1 the amendments do not list " +
        "(the scenario above), which is what makes a breaking change a new prefix; serving the prior one for six months is " +
        "the release's, not a test's (contracts/amendments.md, Why /v1)"))

  /** Each operation's path with its ids filled: the seeded records', a game code, and the query a ranking needs. */
  private def filled(template: String): String =
    val s = Seeded.set
    val ids = Map("playerId" -> s.players.head, "sessionId" -> s.sessions(0), "roundId" -> s.rounds(0), "betId" -> s.bets(0),
      "transactionId" -> s.transactions(0), "bonusAwardId" -> s.bonuses(0), "gameId" -> "g-none")
    val path = "\\{([A-Za-z]+)\\}".r.replaceAllIn(template, m => ids(m.group(1)))
    path + (if template.endsWith("-stats") then "?period=7d&sort=rounds" else "")

  Given("a caller with no access key") { () => () }
  When("it fetches the public contract") { () => last = kit.v1("/openapi.json") }
  Then("it receives every operation, parameter, answer and refusal, and the maximum page size") { () =>
    assertEquals(last.status, 200)
    val d = last.json
    assertEquals(d.path("paths").size, 22)
    assertEquals(d.path("components").path("parameters").path("limit").path("schema").path("maximum").asInt(), 100)
    Vector("BadRequest", "Unauthorized", "Forbidden", "NotFound", "TooManyRequests", "Unavailable", "SnapshotExpired")
      .foreach(r => assert(d.path("components").path("responses").has(r), r))
    d.path("paths").properties().asScala.foreach(e => assert(e.getValue.path("get").path("responses").has("200"), e.getKey))
  }
  Then("a client generated from it reads every operation with an access key") { () =>
    val o = ParseOptions(); o.setResolve(true); o.setResolveFully(true)
    val api = OpenAPIParser().readContents(last.text, null, o).getOpenAPI
    val base = api.getServers.get(0).getUrl
    assert(base.endsWith("/v1"), base)
    api.getPaths.asScala.foreach { (template, item) =>
      val r = kit.v1(filled(template), Some(key.secret))
      assert(Set(200, 404)(r.status), s"${item.getGet.getOperationId}: ${r.status} ${r.text.take(200)}")
      assertEquals(ContractSchemas.problems(template, r.status, r.json), Vector.empty, template)
    }
  }

  Given("every operation of the public contract") { () => Seeded.set: Unit }
  When("each is asked for with valid and invalid requests") { () =>
    answers = Contract.file().path("paths").fieldNames().asScala.toVector.flatMap { t =>
      val ok = filled(t)
      Vector(t -> kit.v1(ok, Some(key.secret)), t -> kit.v1(ok, None), t -> kit.v1(ok, Some("ak_nobody.secret")),
        t -> kit.v1(ok.replace(Seeded.set.players.head, "not-a-uuid").replace("g-none", "bad%20code").replace(Seeded.set.sessions(0), Missing)
          + (if ok.contains('?') then "&limit=0" else "?limit=0"), Some(key.secret)))
    }
  }
  Then("every answer matches the public contract") { () =>
    answers.foreach { (t, r) =>
      val documented = ContractSchemas.statuses(t)
      if t == "/openapi.json" then assertEquals(r.status, 200)
      else
        assert(documented(r.status), s"GET $t answered ${r.status}, which it does not document")
        assertEquals(ContractSchemas.problems(t, r.status, r.json), Vector.empty, s"GET $t ${r.status}")
    }
  }

  Given("a release that does not intend to change the public contract") { () => () }
  When("it is built") { () => served = kit.v1("/openapi.json").json }
  Then("it serves an identical public contract, or the build fails") { () =>
    val file = Contract.file().deepCopy[ObjectNode]()
    file.path("servers").get(0).asInstanceOf[ObjectNode].put("url", served.path("servers").get(0).path("url").asText())
    assertEquals(served, file)
    val amendments = ContractAmendmentSuite()
    val original = ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
      .readTree(java.nio.file.Paths.get("../../specs/016-public-api/contracts/original/public-api-v1.openapi.yaml").toFile)
    assertEquals(amendments.differences(original, Contract.file()).filterNot(amendments.allowed), Vector.empty)
    val changed = Contract.file().deepCopy[ObjectNode]()
    changed.path("components").path("schemas").path("Round").path("required").asInstanceOf[com.fasterxml.jackson.databind.node.ArrayNode].add("status")
    assert(amendments.differences(original, changed).filterNot(amendments.allowed).nonEmpty, "the drift check did not see a change")
  }
