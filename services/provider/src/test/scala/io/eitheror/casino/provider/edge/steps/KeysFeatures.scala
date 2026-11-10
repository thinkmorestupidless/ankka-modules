package io.eitheror.casino.provider.edge.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing, ScriptedService}
import io.eitheror.casino.provider.edge.ProviderTestKit
import io.eitheror.casino.provider.edge.ProviderTestKit.staff
import io.eitheror.casino.provider.st8.Signer

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/games/keys.feature (US4): `goldvibe` holds the key (its fake reads the provider's key
 * ring, as a brand that was handed the key does); the brand naming the key document is played by
 * reading `/.well-known/st8-keys` as spec 003's brand reads it (`keys[].publicKey`) and verifying
 * what the fake was sent against it, fresh or as fetched before the switch, within the bound.
 */
class KeysFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/games/keys.feature") with LogCapturing:

  override val munitTimeout = 2.minutes

  private val mapper = ObjectMapper()
  private def kit    = ProviderTestKit.kit
  private def game   = kit.game
  private def json(text: String): JsonNode = mapper.readTree(text)
  private val operator = staff("games")

  private var document: JsonNode = null
  private var cached: Vector[String] = Vector.empty
  private var answer: ProviderTestKit.Answer = null

  override def afterEach(context: AfterEach): Unit = kit.restoreGoldvibe()

  private def keysNow: JsonNode = kit.asStaff("GET", "/v1/provider/keys", operator).json
  private def read(): JsonNode  = json(kit.call("GET", "/.well-known/st8-keys").body)

  /** The public keys a brand naming the document would verify under, as spec 003 reads them. */
  private def listed(doc: JsonNode): Vector[String] = doc.get("keys").elements().asScala.map(_.get("publicKey").asText()).toVector

  private def verifiesUnder(r: ScriptedService.Request, publicKeys: Vector[String]): Boolean =
    r.header(Signer.Header).exists(sig => publicKeys.exists(k => Signer.verify(r.body, sig, Signer.publicKey(k))))

  private def publicOf(kid: String): String = Signer.publicKeyText(kit.keys.publicOf(kid).get)

  /** One provider call to goldvibe, signed as every call is: a game page's check of a new launch. */
  private def oneCall(): ScriptedService.Request =
    val ticket = s"L-keys-${System.nanoTime()}"
    kit.brand.clear()
    assert(kit.launch(ticket, s"p-keys-${System.nanoTime()}", "test-slot").json.has("game_url"))
    assertEquals(game.check(ticket).status, 200)
    kit.brand.requestsTo("check").last

  private def noRotation(): Unit =
    if !keysNow.get("next").isNull then assertEquals(kit.asStaff("POST", "/v1/provider/keys/switch", operator).status, 200)
    if !keysNow.get("previous").isNull then assertEquals(kit.asStaff("POST", "/v1/provider/keys/withdraw", operator).status, 200)

  Given("a house provider serving two brands, one holding its key and one naming its key document") { () =>
    kit.start()
    game.up()
    assertEquals(game.registerGame("test-slot", "slots").status, 200)
  }

  Given("no key rotation in progress") { () => noRotation() }

  When("the key document is read") { () => document = read() }

  Then("it names the current signing key by id and no next key") { () =>
    assertEquals(document.get("current").get("kid").asText(), kit.keys.currentKeyId)
    assert(!document.has("next"), document.toString)
    assertEquals(listed(document), Vector(publicOf(kit.keys.currentKeyId)))
  }

  When("an operator starts a key rotation") { () =>
    answer = kit.asStaff("POST", "/v1/provider/keys/start", operator)
    assertEquals(answer.status, 200, answer.body)
  }

  Then("the key document names the current key and the next key by id") { () =>
    val doc = read()
    assertEquals(doc.get("current").get("kid").asText(), kit.keys.currentKeyId)
    assertEquals(Some(doc.get("next").get("kid").asText()), kit.keys.nextKeyId)
    assertEquals(listed(doc).toSet, Set(publicOf(kit.keys.currentKeyId), publicOf(kit.keys.nextKeyId.get)))
  }

  Then("every provider call is still signed with the current key") { () =>
    val call = oneCall()
    assert(verifiesUnder(call, Vector(publicOf(kit.keys.currentKeyId))))
    assert(!verifiesUnder(call, Vector(publicOf(kit.keys.nextKeyId.get))))
  }

  Given("a key rotation started") { () =>
    noRotation()
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/start", operator).status, 200)
  }

  private var next = ""
  private var old  = ""

  When("an operator switches the key and then withdraws the old one") { () =>
    old = kit.keys.currentKeyId
    next = kit.keys.nextKeyId.get
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/switch", operator).status, 200)
    // Between the acts the old key is still listed, for a call it signed that is on its way.
    assertEquals(listed(read()).toSet, Set(publicOf(next), publicOf(old)))
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/withdraw", operator).status, 200)
    // The withdrawn key's private half is gone from the secret store (review); its public half stays.
    assert(!kit.keys.holdsPrivate(old))
    assert(kit.keys.publicOf(old).isDefined)
  }

  Then("every later provider call is signed with the next key") { () =>
    val call = oneCall()
    assert(verifiesUnder(call, Vector(publicOf(next))))
    assert(!verifiesUnder(call, Vector(publicOf(old))))
  }

  Then("the key document names only the new key") { () =>
    val doc = read()
    assertEquals(doc.get("current").get("kid").asText(), next)
    assert(!doc.has("next"))
    assertEquals(listed(doc), Vector(publicOf(next)))
  }

  Given("a key rotation started and the brand naming the key document") { () =>
    noRotation()
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/start", operator).status, 200)
    // The brand's copy, fetched before the switch; it refreshes within its bound (1 s here).
    cached = listed(read())
  }

  When("the operator switches the key") { () =>
    next = kit.keys.nextKeyId.get
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/switch", operator).status, 200)
  }

  Then("the brand admits provider calls signed with the new key within its configured bound") { () =>
    val call = oneCall()
    assert(verifiesUnder(call, Vector(publicOf(next))), "signed with the new key")
    // Its copy from before the switch already lists the new key as the next, so no call is refused
    // while the copy is stale; and a copy refreshed within the bound lists it too.
    assert(verifiesUnder(call, cached), "admitted by the copy fetched before the switch")
    val bound = kit.cache.entry("goldvibe").get.keyRefreshBoundSeconds.seconds
    Thread.sleep(bound.toMillis)
    assert(verifiesUnder(call, listed(read())), "admitted by the copy refreshed within the bound")
  }

  Given("a brand entry naming the key document for a brand that takes real money") { () =>
    val written = kit.asStaff("PUT", "/v1/provider/brands/goldvibe", operator,
      Some(kit.entryBody("goldvibe", kit.brand.address).replace("\"realMoney\":false", "\"realMoney\":true,\"keyDocument\":true")))
    assertEquals(written.status, 400, "the write of the pair is refused")
    assert(written.body.contains("keyDocument"), written.body)
    // An entry that reached the table some other way (an older write) is still refused at launch.
    kit.cache.put(kit.entry("goldvibe", kit.brand.address).copy(realMoney = true, keyDocument = true))
  }

  When("a launch for that brand arrives") { () =>
    answer = kit.launch(s"L-real-${System.nanoTime()}", "p-real", "test-slot")
  }

  Then("the house provider refuses it naming the brand entry") { () =>
    assertEquals(answer.json.get("error").asText(), "brand_entry_refused: keyDocument with realMoney in the entry of goldvibe")
  }
