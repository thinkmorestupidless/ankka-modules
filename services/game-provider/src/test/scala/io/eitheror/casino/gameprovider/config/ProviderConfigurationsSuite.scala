package io.eitheror.casino.gameprovider.config

import com.thinkmorestupidless.ankka.sdk.SecretStore
import io.eitheror.casino.gameprovider.TestKeys

import java.time.{Duration, Instant}
import scala.collection.mutable

class ProviderConfigurationsSuite extends munit.FunSuite:

  /** A secret store that counts its reads, as 038's read records would. */
  final class FakeSecrets extends SecretStore:
    val values = mutable.Map.empty[String, String]
    var reads  = 0
    def put(name: String, value: String): Unit = values.update(name, value)
    def get(name: String): Option[String]      = { reads += 1; values.get(name) }
    def delete(name: String): Unit             = values.remove(name): Unit

  final class FakeDocuments extends KeyDocuments:
    var keys: Either[String, Vector[String]] = Right(Vector(TestKeys.testPublicDer))
    var fetches                              = 0
    def fetch(url: String) = { fetches += 1; keys }

  private var clock = Instant.parse("2026-10-09T10:00:00Z")
  private def configured(secrets: SecretStore, docs: KeyDocuments = FakeDocuments()) =
    ProviderConfigurations(secrets, docs, Duration.ofSeconds(30), () => clock)

  private val literal = ProviderConfiguration(
    apiUrl = "http://st8", site = "goldvibe", operatorPrivateKey = TestKeys.testPrivateDer,
    providerPublicKey = Some(TestKeys.testPublicDer), jurisdiction = "MT", lobbyUrl = "l", depositUrl = "d")

  override def beforeEach(context: BeforeEach): Unit = clock = Instant.parse("2026-10-09T10:00:00Z")

  test("a configuration written is read back, listed, and its private key is not in the view") {
    val secrets = FakeSecrets()
    val configs = configured(secrets)
    configs.put("st8", literal)
    assertEquals(configs.get("st8").map(_.site), Some("goldvibe"))
    assertEquals(configs.providers, Vector("st8"))
    val view = configs.get("st8").get.view("st8")
    assert(!view.toString.contains(TestKeys.testPrivateDer), "the operator private key never appears on a read")
    assertEquals(configs.get("st8").get.updatedAt, Some(clock))
  }

  test("reads are cached for 30 seconds, so a callback does not read the store each time") {
    val secrets = FakeSecrets()
    configured(secrets).put("st8", literal)
    val configs = configured(secrets)
    secrets.reads = 0
    (1 to 100).foreach(_ => configs.get("st8"))
    assertEquals(secrets.reads, 1)
    clock = clock.plusSeconds(31)
    configs.get("st8")
    assertEquals(secrets.reads, 2)
  }

  test("another instance's write is seen within the bound; this instance's at once") {
    val secrets = FakeSecrets()
    val here    = configured(secrets)
    val there   = configured(secrets)
    here.put("st8", literal)
    assertEquals(there.get("st8").map(_.jurisdiction), Some("MT"))
    here.put("st8", literal.copy(jurisdiction = "CW"))
    assertEquals(here.get("st8").map(_.jurisdiction), Some("CW"), "the writer forgets its cache")
    assertEquals(there.get("st8").map(_.jurisdiction), Some("MT"), "the other instance within 30 s")
    clock = clock.plusSeconds(30)
    assertEquals(there.get("st8").map(_.jurisdiction), Some("CW"))
  }

  test("a literal public key verifies; an unconfigured provider has no key") {
    val secrets = FakeSecrets()
    val configs = configured(secrets)
    configs.put("st8", literal)
    assertEquals(configs.verifyingKeys("st8"), Vector(TestKeys.testPublic))
    assertEquals(configs.verifyingKeys("other"), Vector.empty)
    assert(configs.operatorKey("st8").isDefined)
  }

  test("a key document is fetched, refreshed within 60 seconds, and lists the next key during a rotation") {
    val secrets = FakeSecrets()
    val docs    = FakeDocuments()
    val configs = configured(secrets, docs)
    configs.put("st8", literal.copy(providerPublicKey = None, providerKeyDocumentUrl = Some("http://sim/keys")))
    assertEquals(configs.verifyingKeys("st8"), Vector(TestKeys.testPublic))
    docs.keys = Right(Vector(TestKeys.testPublicDer, TestKeys.otherPublicDer))
    assertEquals(configs.verifyingKeys("st8").size, 1, "cached")
    clock = clock.plusSeconds(31)
    assertEquals(configs.verifyingKeys("st8").toSet, Set(TestKeys.testPublic, TestKeys.otherPublic))
  }

  test("an unreachable key document verifies nothing and is asked again next time") {
    val secrets = FakeSecrets()
    val docs    = FakeDocuments()
    docs.keys = Left("down")
    val configs = configured(secrets, docs)
    configs.put("st8", literal.copy(providerPublicKey = None, providerKeyDocumentUrl = Some("http://sim/keys")))
    assertEquals(configs.verifyingKeys("st8"), Vector.empty)
    docs.keys = Right(Vector(TestKeys.testPublicDer))
    assertEquals(configs.verifyingKeys("st8"), Vector(TestKeys.testPublic))
  }

  test("a write is refused when a key is missing, both are given, or a real-money brand names a document") {
    assertEquals(literal.problem(realMoney = true), None)
    assert(literal.copy(providerPublicKey = None).problem(false).isDefined)
    assert(literal.copy(providerKeyDocumentUrl = Some("u")).problem(false).isDefined)
    assert(literal.copy(providerPublicKey = None, providerKeyDocumentUrl = Some("u")).problem(true).get.startsWith("real_money_brand"))
    assertEquals(literal.copy(providerPublicKey = None, providerKeyDocumentUrl = Some("u")).problem(false), None)
    assert(literal.copy(operatorPrivateKey = "nope").problem(false).isDefined)
  }

  test("two providers are listed in the order written") {
    val configs = configured(FakeSecrets())
    configs.put("st8", literal)
    configs.put("acme", literal)
    configs.put("st8", literal)
    assertEquals(configs.providers, Vector("st8", "acme"))
  }
