package io.eitheror.casino.attribution.features

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{IncomingMessage, TopicSubscription}
import com.thinkmorestupidless.ankka.sdk.StartFrom
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.carriers.{Delivery, DeliveryRules, Pages}
import io.eitheror.casino.attribution.it.{Kit, Reply}
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact, PlayerAttributionFact}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/attribution/carriers.feature (US3): the topic, the signed feed and the webhooks. Kits of its own
 * (build.sbt's `own`): it moves the clock a day, and "two brands" runs a second brand beside the first.
 */
class CarriersFeatures extends AttributionFeatures("carriers.feature"):

  private lazy val own: Kit = prepared(Kit())
  private lazy val second: Kit = prepared(Kit(brand = "silvervibe"))
  override protected def kit: Kit = own

  override def afterAll(): Unit =
    own.stop()
    if secondStarted then second.stop()

  private var secondStarted = false

  private def prepared(k: Kit): Kit =
    k.rewire()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k.components.forKeyValueEntity(EntityId("programme")).call(Network.setMachine).invoke("acme/reader"): Unit
    val made = k.request("POST", s"/v1/casino/backoffice/${k.brand}/attribution/signing-keys", headers = k.asOperator())
    assertEquals(made.status, 201, made.text)
    k

  // ── a scenario's state ──────────────────────────────────────────────────────────────────────────

  private var factId: String        = null
  private var subscription: String  = null
  private var emittedAt: Long       = 0L
  private var read                  = mutable.ArrayBuffer.empty[IncomingMessage]
  private val readers               = mutable.ArrayBuffer.empty[() => Unit]
  private val pages                 = mutable.Map.empty[String, Reply]
  private val cursors               = mutable.Map.empty[String, String]
  private var page: Reply           = null
  private var all: Vector[String]   = Vector.empty
  private var both: Vector[PlayerAttributionFact] = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    own.rewire()
    super.beforeEach(context)
    factId = null; subscription = null; emittedAt = 0L; page = null; all = Vector.empty; both = Vector.empty
    readers.foreach(_()); readers.clear()
    read = mutable.ArrayBuffer.empty; pages.clear(); cursors.clear()
    val left = kit.request("GET", webhooks, headers = kit.asOperator()).json.get("subscriptions").elements.asScala.map(_.get("id").asText)
    left.foreach(id => kit.request("DELETE", s"$webhooks/$id", headers = kit.asOperator()): Unit)

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private def webhooks = s"/v1/casino/backoffice/${kit.brand}/attribution-webhooks"

  private def feedPath(cursor: Option[String] = None, limit: Option[Int] = None) =
    val q = (cursor.map(c => s"cursor=$c") ++ limit.map(l => s"limit=$l")).mkString("&")
    s"/v1/casino/brands/${kit.brand}/attribution/networks/programme/feed" + (if q.isEmpty then "" else s"?$q")

  private def machine = kit.asMachine("acme", "reader")

  private def pseudonym(k: Kit, player: String) =
    Pseudonyms.derive(Pseudonyms.keyBytes(k.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)

  /** A player bound by a fresh link; answers the registration's fact id. */
  private def emit(k: Kit = kit, affiliate: String = "aff-1", player: Option[String] = None): String =
    val p = k.bindPlayer(Some(k.network.mint(affiliate, k.fresh("lnk"))), player = player.getOrElse(k.fresh("p")))
    s"registration/${pseudonym(k, p)}"

  /** A reader of the topic under a group of its own, from the earliest message. */
  private def reader(group: String): Unit =
    val into = read
    val subscribed = kit.broker.subscribe(TopicSubscription(AffiliatesAttribution.name, s"$group-${kit.fresh("g")}", StartFrom.Earliest),
      m => { into.synchronized(into += m); Future.successful(org.apache.pekko.Done) })
    readers += (() => subscribed.stop())

  private def readIds = read.synchronized(read.toVector).flatMap(_.metadata.get("ce-id"))

  private def ids(r: Reply): Vector[String] = r.json.get("facts").elements.asScala.toVector.map(_.get("fact_id").asText)

  private def feedHolding(want: String*): Vector[String] =
    kit.eventually(s"the feed holding ${want.mkString(", ")}") {
      val r = kit.request("GET", feedPath(), headers = machine)
      Option.when(r.status == 200 && want.forall(ids(r).contains))(ids(r))
    }

  private def publicKey: String =
    kit.request("GET", s"/v1/casino/brands/${kit.brand}/attribution/keys", headers = kit.fromInternet).json.get("keys").get(0).get("x").asText

  private def postsOf(id: String) = kit.receiver.posts.filter(_.headers.get("x-attribution-fact-id").contains(id))

  private def delivery = kit.components.forWorkflow(Delivery.id(subscription, factId)).call(Delivery.status).invoke()

  // ── the topic ───────────────────────────────────────────────────────────────────────────────────

  Given("the programme is allowed to read the brand's attribution facts") { () => reader("programme") }
  When("the brand emits a fact") { () =>
    emittedAt = System.nanoTime()
    factId = emit()
  }
  Then("the programme reads it within a minute") { () =>
    kit.eventually("the programme's read", 60.seconds)(Option.when(readIds.contains(factId))(()))
    assert((System.nanoTime() - emittedAt).nanos < 60.seconds)
  }
  Then("the fact id, the kind and the protocol version are readable without opening the fact") { () =>
    val m = read.synchronized(read.find(_.metadata.get("ce-id").contains(factId))).get
    assertEquals(m.metadata.get("ce-id"), Some(factId))
    assertEquals(factId.takeWhile(_ != '/'), "registration")
    assertEquals(m.metadata.get("ce-type"), Some(AffiliatesAttribution.contractName))
    assert(AffiliatesAttribution.contractName.endsWith(".v1"))
  }

  Given("a network registered as a machine and allowed to read the brand's attribution facts") { () =>
    val n = kit.components.forKeyValueEntity(EntityId("programme")).call(Network.get).invoke()
    assertEquals(n.machine, Some("acme/reader"))
  }
  When("it reads them from the installation with its own reader") { () =>
    factId = emit()
    reader("acme-reader")
  }
  Then("it reads the same facts from its own position") { () =>
    val brand = kit.eventually("the brand's fact")(Option.when(kit.attributionMessages.exists(_.message.metadata.get("ce-id").contains(factId)))(
      kit.attributionMessages.flatMap(_.message.metadata.get("ce-id"))))
    kit.eventually("every fact read")(Option.when(readIds.size >= brand.size)(()))
    assertEquals(readIds.take(brand.size), brand)
  }
  Then("it is refused every other fact of the brand") { () =>
    // The topic's ACL is the installation's grant (quickstart §7, T073); here, every other route of the brand.
    assertEquals(kit.request("GET", s"/v1/casino/brands/${kit.brand}/attribution/networks/other/feed", headers = machine).status, 404)
    def refused(r: Reply) = assert(r.status == 401 || r.status == 403, s"${r.status}: ${r.text}")
    refused(kit.request("GET", s"/v1/casino/backoffice/${kit.brand}/attribution/settings", headers = machine))
    refused(kit.request("GET", webhooks, headers = machine))
    assertEquals(kit.request("POST", s"/v1/casino/brands/${kit.brand}/attribution/players/p-1/erasure", headers = machine).status, 403)
  }

  // ── the feed ────────────────────────────────────────────────────────────────────────────────────

  Given("a network with no reader of its own and a machine's sign-in") { () =>
    val ours = Vector(emit(), emit(), emit())
    all = feedHolding(ours*)
  }
  When("it reads the brand's feed from a cursor") { () =>
    val first = kit.request("GET", feedPath(limit = Some(1)), headers = machine)
    assertEquals(first.status, 200, first.text)
    cursors("first") = first.json.get("cursor").asText
    page = kit.request("GET", feedPath(cursor = cursors.get("first")), headers = machine)
    assertEquals(page.status, 200, page.text)
  }
  Then("it receives facts from that cursor in the order they were emitted") { () => assertEquals(ids(page), all.drop(1)) }
  Then("each page carries a signature over its facts under the brand's signing key") { () =>
    assert(Pages.verify(page.json, publicKey), page.text)
  }
  Then("each page carries a cursor to continue from") { () =>
    val next = kit.request("GET", feedPath(cursor = Some(page.json.get("cursor").asText)), headers = machine)
    assertEquals((next.status, ids(next)), (200, Vector.empty))
  }

  Given("a network that read the brand's feed from cursor {string}") { (name: String) =>
    feedHolding(emit(), emit()): Unit
    cursors(name) = kit.request("GET", feedPath(limit = Some(1)), headers = machine).json.get("cursor").asText
    pages(name) = kit.request("GET", feedPath(cursor = cursors.get(name)), headers = machine)
  }
  When("it reads the feed from cursor {string} again") { (name: String) =>
    page = kit.request("GET", feedPath(cursor = cursors.get(name)), headers = machine)
    pages("again") = pages(name)
  }
  Then("it receives the same page") { () =>
    assertEquals(page.status, 200)
    assertEquals(page.text, pages("again").text)
  }

  // ── the webhooks ────────────────────────────────────────────────────────────────────────────────

  Given("a network webhook the brand's operator created") { () =>
    kit.receiver.default = Some(503)
    val body = Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl, "secret" -> kit.receiver.secret))
    val made = kit.request("POST", webhooks, Some(body), kit.asOperator())
    assertEquals(made.status, 201, made.text)
    subscription = made.json.get("id").asText
    kit.eventually("listed")(Option.when(kit.request("GET", s"$webhooks?network=programme", headers = kit.asOperator()).text.contains(subscription))(()))
  }
  When("a fact is emitted") { () => factId = emit() }
  Then("the brand posts the fact to the network webhook's address") { () =>
    val post = kit.eventually("the post")(postsOf(factId).headOption)
    assert(kit.receiver.signed(post))
    assertEquals(post.path, "/hook")
  }
  Then("the brand retries with backoff on failure for a day") { () =>
    kit.clock.moveBy(1.minute)
    kit.eventually("the retry after a minute")(Option.when(postsOf(factId).size == 2)(()))
    var hours = 0
    while delivery.status != DeliveryRules.Undelivered do
      assert(hours < 30, s"still retrying: $delivery")
      val before = postsOf(factId).size
      kit.clock.moveBy(1.hour)
      kit.eventually("another attempt")(Option.when(postsOf(factId).size > before)(()))
      hours += 1
    assert(!kit.now.isBefore(delivery.firstAttemptAt.get.plusSeconds(24 * 3600)))
  }
  Then("after that the fact is kept as an undelivered fact the operator can see and resend") { () =>
    kit.eventually("listed undelivered")(Option.when(
      kit.request("GET", s"$webhooks/undelivered?network=programme", headers = kit.asOperator()).text.contains(factId))(()))
    kit.receiver.default = Some(200)
    val id = URLEncoder.encode(s"$subscription/$factId", UTF_8)
    assertEquals(kit.request("POST", s"$webhooks/undelivered/$id/resend", headers = kit.asOperator()).status, 200)
    kit.eventually("delivered")(Option.when(delivery.status == DeliveryRules.Delivered)(()))
  }

  // ── reading twice ───────────────────────────────────────────────────────────────────────────────

  Given("a reader on any carrier") { () =>
    reader("any")
    kit.receiver.default = Some(200)
    val body = Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl, "secret" -> kit.receiver.secret))
    subscription = kit.request("POST", webhooks, Some(body), kit.asOperator()).json.get("id").asText
    kit.eventually("listed")(Option.when(kit.request("GET", s"$webhooks?network=programme", headers = kit.asOperator()).text.contains(subscription))(()))
  }
  When("it receives a fact whose id it has seen") { () =>
    factId = emit()
    kit.eventually("read once")(Option.when(readIds.contains(factId))(()))
    val m = kit.attributionMessages.find(_.message.metadata.get("ce-id").contains(factId)).get.message
    Await.result(kit.broker.publish(AffiliatesAttribution.name, m.key, m.payload, m.metadata), 10.seconds): Unit
    kit.eventually("read twice")(Option.when(readIds.count(_ == factId) == 2)(()))
  }
  Then("it can discard the fact, because the id and the content are the same both times") { () =>
    val twice = read.synchronized(read.filter(_.metadata.get("ce-id").contains(factId)).toVector)
    assertEquals(twice.map(m => String(m.payload, UTF_8)).distinct.size, 1)
    // The brand's own carriers dropped the second: once on the feed, one post.
    assertEquals(feedHolding(factId).count(_ == factId), 1)
    kit.eventually("the post")(postsOf(factId).headOption): Unit
    Thread.sleep(1500)
    assertEquals(postsOf(factId).size, 1)
  }

  // ── two brands ──────────────────────────────────────────────────────────────────────────────────

  private def factOf(k: Kit, id: String): PlayerAttributionFact =
    k.eventually(s"$id on ${k.brand}")(k.attributionFacts.collectFirst { case f: PlayerAttributionFact if f.factId == id => f })

  Given("an affiliate who sends traffic to two brands") { () =>
    secondStarted = true
    second: Unit
    own.rewire()
  }
  When("both brands emit facts") { () =>
    val player = kit.fresh("p")
    val a = factOf(own, emit(own, "aff-shared", Some(player)))
    second.rewire()
    val b = factOf(second, emit(second, "aff-shared", Some(player)))
    own.rewire()
    both = Vector(a, b)
  }
  Then("each fact names its brand") { () => assertEquals(both.map(_.brand), Vector("goldvibe", "silvervibe")) }
  Then("the affiliate's id is the same in both") { () => assertEquals(both.map(_.affiliate).distinct, Vector("aff-shared")) }
  Then("the player pseudonyms differ") { () => assertEquals(both.map(_.pseudonym).distinct.size, 2) }
