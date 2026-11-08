package io.eitheror.casino.engagement.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.engagement.Json

import java.util.UUID

/**
 * features/engagement/transactional.feature (US4; FR-013, FR-014): the send route called as `players` of this project,
 * the brand's trigger table holding an id for `EmailAddressVerification` and none for `PasswordChangeNotification`,
 * which has no built-in fallback either.
 */
class TransactionalFeatures extends EngagementFeatures("transactional.feature"):
  private var body: JsonNode               = null
  private var caller: Seq[(String, String)] = Nil
  private var first                        = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    body = null
    caller = kit.asService("players")
    first = ""

  private def to   = s"$player@example.com"
  private def link = s"https://${kit.slug}.example/verify?token=t-$player"
  private def mine = kit.crm.sends.filter(_.path("to").asText == to)

  private def requestFor(trigger: String): JsonNode =
    Json.obj("key" -> UUID.randomUUID().toString, "playerId" -> player, "trigger" -> trigger, "to" -> to, "link" -> link)

  private def post(): Unit = last = kit.json("POST", kit.engagementPath("/sends"), body, caller)

  Given("brand {string}") { (slug: String) =>
    assertEquals(kit.slug, slug)
    register()
  }

  Given("a request from the brand's player records naming the trigger {string}, a recipient and a verification address") {
    (trigger: String) => body = requestFor(trigger)
  }

  Given("a request from the brand's player records already served under its key") { () =>
    body = requestFor("EmailAddressVerification")
    post()
    assertEquals((last.status, last.str("status")), (200, "accepted"), last.text)
    first = last.text
    kit.crm.reset()
  }

  Given("a trigger with no transactional id configured and no fallback template") { () =>
    body = requestFor("PasswordChangeNotification")
  }

  Given("a caller that is not a part of the brand") { () =>
    body = requestFor("EmailAddressVerification")
    caller = kit.asService("players", project = "another-brand")
  }

  When("it is received") { () => post() }
  When("the same request is received again under that key") { () => post() }
  When("a send is requested for it") { () => post() }
  When("it requests a send") { () => post() }

  Then("one transactional send is made with the verification address and the brand name as data") { () =>
    val sends = eventually("the transactional send")(Option(mine).filter(_.nonEmpty))
    assertEquals(sends.size, 1)
    val data = sends.head.path("message_data")
    assertEquals((data.path("verification_link").asText, data.path("brand_name").asText), (link, kit.slug))
    assertEquals(sends.head.path("transactional_message_id").asText, EngagementFeatures.Triggers("EmailAddressVerification"))
  }

  Then("the answer carries the send id and {string}") { (status: String) =>
    assertEquals(last.status, 200, last.text)
    assert(last.str("sendId").endsWith(body.path("key").asText), last.text)
    assertEquals(last.str("status"), status)
  }

  Then("no send is made") { () =>
    Thread.sleep(1000)
    assertEquals(mine, Vector.empty)
  }

  Then("the answer is the first") { () => assertEquals((last.status, last.text), (200, first)) }

  Then("nothing is sent") { () =>
    Thread.sleep(500)
    assertEquals(mine, Vector.empty)
  }

  Then("the answer says {string}") { (status: String) =>
    assertEquals((last.status, last.str("status")), (200, status), last.text)
  }

  Then("it is refused") { () =>
    assert(last.status == 401 || last.status == 403, s"${last.status} ${last.text}")
    Thread.sleep(500)
    assertEquals(mine, Vector.empty)
  }
