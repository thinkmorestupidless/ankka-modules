package io.eitheror.casino.audit.features

import io.eitheror.casino.audit.api.IdentitiesRoute
import io.eitheror.casino.audit.it.Reply
import io.eitheror.casino.audit.stored.Visibility

/** features/audit/re-identification.feature (US4), through `/v1/audit-identities`. */
class ReidentificationFeatures extends AuditFeatures("re-identification.feature"):
  override protected def ranElsewhere: Map[String, String] = ReidentificationFeatures.Elsewhere

  private var officer: Seq[(String, String)] = Nil
  private var answer: Reply                  = null
  private var pseudonym                      = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    officer = Nil; answer = null; pseudonym = ""

  /** A pseudonym of a goldvibe player, issued by the lookup route as a re-identifier. */
  private def issued(): String =
    val r = kit.identities("pseudonym", kit.asReidentifier("goldvibe", id("setup")),
      s"""{"brand":"goldvibe","kind":"player","id":"${id("player")}","reasonCode":"setup"}""")
    r.json.get("pseudonym").asText

  Given("a compliance officer with the re-identification role for brand {string}") { (b: String) =>
    officer = kit.asReidentifier(b, id("officer"))
  }

  Given("a compliance officer without the re-identification role") { () => officer = kit.asBrandCompliance("goldvibe", id("officer")) }

  When("they ask who a pseudonym of brand {string} is, giving a reason code") { (_: String) =>
    pseudonym = issued()
    answer = kit.identities("who", officer, s"""{"pseudonym":"$pseudonym","reasonCode":"regulator_request"}""")
  }

  Then("they are told the brand, the kind, player, operator, affiliate or service, and the real id") { () =>
    assertEquals(answer.status, 200, answer.text)
    assertEquals((answer.json.get("brand").asText, answer.json.get("kind").asText, answer.json.get("id").asText),
      ("goldvibe", "player", id("player")))
  }

  Then("a record of the re-identification names them, the pseudonym and the reason") { () =>
    val made = kit.eventually()(kit.own(IdentitiesRoute.Who).find(r => r.subject == pseudonym && r.outcome == "done"))
    assert(made.actor.nonEmpty && made.actor != id("officer"))
    assertEquals(made.visibility, Visibility.Brand)
    assert(made.changes.exists(c => c.field == "reason" && c.after.contains("regulator_request")))
  }

  When("they ask who a pseudonym is") { () =>
    pseudonym = issued()
    answer = kit.identities("who", officer, s"""{"pseudonym":"$pseudonym","reasonCode":"curious"}""")
  }

  Then("they are refused") { () => assertEquals((answer.status, answer.code), (403, IdentitiesRoute.NotReidentifier)) }

  Then("the refusal is recorded") { () =>
    val made = kit.eventually()(kit.own(IdentitiesRoute.Who).find(r => r.subject == pseudonym && r.outcome == "refused"))
    assertEquals(made.reasonCode, Some(IdentitiesRoute.NotReidentifier))
  }

  When("they ask for the pseudonym of a real id at brand {string}") { (b: String) =>
    answer = kit.identities("pseudonym", officer, s"""{"brand":"$b","kind":"player","id":"${id("real")}","reasonCode":"regulator_request"}""")
  }

  Then("the pseudonym is returned, or issued if none exists") { () =>
    assertEquals(answer.status, 200, answer.text)
    pseudonym = answer.json.get("pseudonym").asText
    assert(answer.json.get("issued").asBoolean)
    val again = kit.identities("pseudonym", officer, s"""{"brand":"goldvibe","kind":"player","id":"${id("real")}","reasonCode":"again"}""")
    assertEquals((again.json.get("pseudonym").asText, again.json.get("issued").asBoolean), (pseudonym, false))
  }

  Then("the lookup is recorded") { () =>
    kit.eventually()(Option(kit.own(IdentitiesRoute.Lookup).filter(_.subject == pseudonym)).filter(_.size == 2)): Unit
  }

object ReidentificationFeatures:
  // Here, not in the class: GherkinSuite reads these while it is constructed.
  private val Elsewhere = Map(
    "a brand operator reading the activity tab sees real ids with no re-identification needed inside the brand" ->
      "services/players' ReidentificationFeatures: the activity lines are the players service's"
  )
