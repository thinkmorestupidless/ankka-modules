package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.carriers.{Delivery, DeliveryRules, Pages}
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, PaymentCaptured, PaymentsPayments}
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The three carriers (US3; FR-018 to FR-022, SC-003): the topic's attributes, the signed feed from a cursor,
 * and the webhooks with their day of retries. A kit of its own, since it moves the clock a day.
 */
class CarriersSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 600.seconds

  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k.components.forKeyValueEntity(EntityId("programme")).call(Network.setMachine).invoke("acme/reader"): Unit
    val made = k.request("POST", s"/v1/casino/backoffice/${k.brand}/attribution/signing-keys", headers = k.asOperator())
    assertEquals(made.status, 201, made.text)
    k

  override def afterAll(): Unit = kit.stop()
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    // Each test counts the posts of its own subscription only: one a failed test left behind is deleted.
    val left = kit.request("GET", webhooks, headers = kit.asOperator()).json.get("subscriptions").elements.asScala.map(_.get("id").asText)
    left.foreach(id => kit.request("DELETE", s"$webhooks/$id", headers = kit.asOperator()): Unit)
    kit.eventually("no subscriptions")(Option.when(kit.request("GET", webhooks, headers = kit.asOperator()).json.get("subscriptions").isEmpty)(()))

  private def feedPath(network: String, cursor: Option[String] = None, limit: Option[Int] = None) =
    val q = (cursor.map(c => s"cursor=$c") ++ limit.map(l => s"limit=$l")).mkString("&")
    s"/v1/casino/brands/${kit.brand}/attribution/networks/$network/feed" + (if q.isEmpty then "" else s"?$q")

  private def webhooks = s"/v1/casino/backoffice/${kit.brand}/attribution-webhooks"

  private def pseudonym(network: String, player: String) =
    Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName(network)).get), player)

  /** A player bound by a fresh link of `fake`'s; answers the registration's fact id. */
  private def bound(fake: FakeNetwork = kit.network, clickId: Option[String] = None): String =
    val player = kit.bindPlayer(Some(fake.mint("aff-1", kit.fresh("lnk"))), clickId = clickId)
    s"registration/${pseudonym(fake.id, player)}"

  private def ids(page: Reply): Vector[String] =
    page.json.get("facts").elements.asScala.toVector.map(_.get("fact_id").asText)

  /** The whole feed as the machine reads it, once it holds `want`. */
  private def feedHolding(network: String, machine: (String, String), want: String*): Vector[String] =
    kit.eventually(s"the $network feed holding ${want.mkString(", ")}") {
      val r = kit.request("GET", feedPath(network), headers = kit.asMachine(machine._1, machine._2))
      Option.when(r.status == 200 && want.forall(ids(r).contains))(ids(r))
    }

  private def publicKey: String =
    kit.request("GET", s"/v1/casino/brands/${kit.brand}/attribution/keys", headers = kit.fromInternet).json
      .get("keys").get(0).get("x").asText

  private def subscribe(): String =
    val body = Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl, "secret" -> kit.receiver.secret))
    val made = kit.request("POST", webhooks, Some(body), kit.asOperator())
    assertEquals(made.status, 201, made.text)
    val id = made.json.get("id").asText
    kit.eventually("the subscription listed") {
      val list = kit.request("GET", s"$webhooks?network=programme", headers = kit.asOperator())
      Option.when(list.text.contains(id))(id)
    }

  private def postsOf(factId: String) = kit.receiver.posts.filter(_.headers.get("x-attribution-fact-id").contains(factId))

  private def delivery(subscription: String, factId: String) =
    kit.components.forWorkflow(Delivery.id(subscription, factId)).call(Delivery.status).invoke()

  private def encoded(subscription: String, factId: String) = URLEncoder.encode(s"$subscription/$factId", UTF_8)

  test("every fact on the topic carries its id and contract in the attributes, its kind and version in the body") {
    val factId  = bound()
    val message = kit.eventually("the fact")(kit.attributionMessages.find(_.message.metadata.get("ce-id").contains(factId)))
    assertEquals(message.message.metadata.get("ce-type"), Some(AffiliatesAttribution.contractName))
    val body = Json.parse(String(message.message.payload, UTF_8)).get
    assertEquals((body.get("type").asText, body.get("protocol_version").asInt, body.get("fact_id").asText), ("registration", 1, factId))
  }

  test("the feed gives the facts in emission order in signed pages, the same page for the same cursor, the rest from the next") {
    val ours = Vector(bound(), bound(), bound())
    val all  = feedHolding("programme", ("acme", "reader"), ours*)
    // Emission order is the topic's: facts about different players are not emitted in the order they bound.
    val emitted = kit.attributionMessages.flatMap(_.message.metadata.get("ce-id")).filter(ours.contains)
    assertEquals(all.filter(ours.contains), emitted)

    val machine = kit.asMachine("acme", "reader")
    val first   = kit.request("GET", feedPath("programme", limit = Some(2)), headers = machine)
    assertEquals(first.status, 200, first.text)
    assertEquals(ids(first), all.take(2))
    assert(Pages.verify(first.json, publicKey), "the page verifies under the key document's key")
    val cursor = first.json.get("cursor").asText
    assert(cursor.nonEmpty)
    assertEquals(kit.request("GET", feedPath("programme", limit = Some(2)), headers = machine).text, first.text)

    val rest = kit.request("GET", feedPath("programme", cursor = Some(cursor)), headers = machine)
    assertEquals(ids(rest), all.drop(2))
    assert(Pages.verify(rest.json, publicKey))
    assertEquals(kit.request("GET", feedPath("programme", cursor = Some(cursor)), headers = machine).text, rest.text)
  }

  test("the feed refuses a limit over 1000, a machine the network does not name, an unknown network and an operator") {
    val machine = kit.asMachine("acme", "reader")
    assertEquals(kit.request("GET", feedPath("programme", limit = Some(1001)), headers = machine).status, 400)
    assertEquals(kit.request("GET", feedPath("programme", cursor = Some("not-a-cursor")), headers = machine).status, 400)
    assertEquals(kit.request("GET", feedPath("programme"), headers = kit.asMachine("acme", "intruder")).status, 404)
    assertEquals(kit.request("GET", feedPath("nobody"), headers = machine).status, 404)
    assertEquals(kit.request("GET", feedPath("programme"), headers = kit.asOperator()).status, 403)
  }

  test("a second network's facts never reach the first network's feed") {
    val other = FakeNetwork("other")
    try
      kit.addNetwork(other, legacy = false, codes = false): Unit
      kit.makePseudonymKey("other")
      kit.components.forKeyValueEntity(EntityId("other")).call(Network.setMachine).invoke("acme/other"): Unit
      val theirs = bound(other)
      val ours   = bound()
      feedHolding("other", ("acme", "other"), theirs): Unit
      val programme = feedHolding("programme", ("acme", "reader"), ours)
      assert(!programme.contains(theirs))
      // The cursor of one network's feed is refused on another's.
      val cursor = kit.request("GET", feedPath("other"), headers = kit.asMachine("acme", "other")).json.get("cursor").asText
      assertEquals(kit.request("GET", feedPath("programme", cursor = Some(cursor)), headers = kit.asMachine("acme", "reader")).status, 400)
    finally other.stop()
  }

  test("a webhook gets each fact once, signed, with the four headers, the registration carrying its click id (SC-003)") {
    val sub    = subscribe()
    val factId = bound(clickId = Some("c-1"))
    val post   = kit.eventually("the post")(postsOf(factId).headOption)
    assert(kit.receiver.signed(post), s"the signature verifies: ${post.headers}")
    assertEquals((post.headers.get("x-attribution-protocol"), post.headers.get("x-attribution-kind")), (Some("1"), Some("registration")))
    assertEquals(post.json.get.get("click_id").asText, "c-1")
    assertEquals(delivery(sub, factId).status, DeliveryRules.Delivered)

    // The broker delivers the same fact again: the feed has it already, so nothing is posted twice.
    val message = kit.attributionMessages.find(_.message.metadata.get("ce-id").contains(factId)).get.message
    Await.result(kit.broker.publish(AffiliatesAttribution.name, message.key, message.payload, message.metadata), 10.seconds): Unit
    Thread.sleep(2000)
    assertEquals(postsOf(factId).size, 1)

    assertEquals(kit.request("DELETE", s"$webhooks/$sub", headers = kit.asOperator()).status, 204)
    assertEquals(kit.testKit.secrets.get(s"webhook/$sub/secret"), None)
    assertEquals(kit.request("GET", s"$webhooks/$sub", headers = kit.asOperator()).status, 404)
  }

  test("a failing webhook is retried 1, 5, 15 and 60 minutes apart, then hourly; after a day the operator resends or discards") {
    kit.receiver.default = Some(503)
    val sub      = subscribe()
    val (a, b)   = (bound(), bound())
    def tries    = (postsOf(a).size, postsOf(b).size)
    // An attempt's next timer is set from the clock after its post; the clock moves only once both deliveries
    // have recorded it, or the timer is set from the moved clock and is never due.
    def recorded = (delivery(sub, a).attempts, delivery(sub, b).attempts)
    kit.eventually("the first attempts")(Option.when(tries == (1, 1) && recorded == (1, 1))(()))

    for (minutes, n) <- Vector(1, 5, 15, 60, 60).zipWithIndex do
      kit.clock.moveBy(minutes.minutes - 10.seconds)
      Thread.sleep(1000)
      assertEquals(tries, (n + 1, n + 1), s"no attempt before $minutes minutes")
      kit.clock.moveBy(10.seconds)
      kit.eventually(s"attempt ${n + 2}")(Option.when(tries == (n + 2, n + 2) && recorded == (n + 2, n + 2))(()))

    var hours = 0
    while delivery(sub, a).status != DeliveryRules.Undelivered || delivery(sub, b).status != DeliveryRules.Undelivered do
      assert(hours < 30, s"still not undelivered after $hours more hours: ${delivery(sub, a)}")
      val before = tries
      kit.clock.moveBy(1.hour)
      kit.eventually("the hourly attempt")(Option.when(tries._1 > before._1 && tries._2 > before._2 && recorded == tries)(()))
      hours += 1
    assertEquals(delivery(sub, a).lastStatus, Some(503))
    assert(!kit.now.isBefore(delivery(sub, a).firstAttemptAt.get.plus(JDuration.ofHours(24))), "undelivered only after 24 hours")

    val undelivered = kit.eventually("both listed") {
      val r = kit.request("GET", s"$webhooks/undelivered?network=programme", headers = kit.asOperator())
      Option.when(r.text.contains(a) && r.text.contains(b))(r)
    }
    assertEquals(undelivered.status, 200)

    kit.receiver.default = Some(200)
    val resent = kit.request("POST", s"$webhooks/undelivered/${encoded(sub, a)}/resend", headers = kit.asOperator())
    assertEquals(resent.status, 200, resent.text)
    kit.eventually("delivered")(Option.when(delivery(sub, a).status == DeliveryRules.Delivered)(()))
    assertEquals(kit.request("POST", s"$webhooks/undelivered/${encoded(sub, a)}/resend", headers = kit.asOperator()).status, 409)

    assertEquals(kit.request("POST", s"$webhooks/undelivered/${encoded(sub, b)}/discard", headers = kit.asOperator()).status, 200)
    kit.eventually("gone from the list") {
      val r = kit.request("GET", s"$webhooks/undelivered?network=programme", headers = kit.asOperator())
      Option.when(!r.text.contains(a) && !r.text.contains(b))(())
    }
    assertEquals(kit.request("POST", s"$webhooks/undelivered/${encoded(sub, "registration/nobody")}/discard",
      headers = kit.asOperator()).status, 404)
  }

  test("a deposit's fact reaches the topic, the feed and the webhook as the registration's does") {
    subscribe(): Unit
    val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
    val p      = pseudonym("programme", player)
    kit.eventually("bound")(kit.record(player).binding): Unit
    val pay = kit.fresh("pay")
    kit.publish(PaymentsPayments, PaymentCaptured(pay, player, "psp", MinorUnits.unsafe(5000), Currency.unsafe("EUR"), kit.now))
    val ids = Vector(s"first-deposit/$p", s"deposit/$p/$pay")
    for id <- ids do
      kit.eventually(s"$id on the topic")(kit.attributionMessages.find(_.message.metadata.get("ce-id").contains(id))): Unit
      val post = kit.eventually(s"$id posted")(postsOf(id).headOption)
      assert(kit.receiver.signed(post))
    val feed = feedHolding("programme", ("acme", "reader"), ids*)
    assert(feed.indexOf(ids(0)) < feed.indexOf(ids(1)), "the first deposit before the deposit")
  }

  test("a webhook is refused an address that is not http(s), and a network the brand does not know") {
    def create(network: String, address: String) =
      kit.request("POST", webhooks, Some(Json.render(Json.obj("network" -> network, "address" -> address, "secret" -> "x"))), kit.asOperator())
    assertEquals(create("programme", "ftp://example.com/hook").status, 400)
    assertEquals(create("nobody", kit.receiver.hookUrl).status, 400)
    assertEquals(kit.request("GET", webhooks, headers = kit.fromInternet).status, 401)
  }
