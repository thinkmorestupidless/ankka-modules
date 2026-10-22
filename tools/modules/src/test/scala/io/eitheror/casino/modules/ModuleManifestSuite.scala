package io.eitheror.casino.modules

import io.eitheror.casino.modules.ModuleManifest.{ContractView, Refusal}

import java.nio.file.Path

/**
 * The manifest's rules (spec 021 contracts/module-manifests.md, FR-002, FR-007) over the broken
 * variants in `src/test/resources/manifests/`, each refused naming the entry. The case that the
 * repository's own manifests under `modules/` validate against the tree's contract is spec 021 T011.
 */
class ModuleManifestSuite extends munit.FunSuite:

  /** The contract as far as these variants need it. */
  private val view = ContractView(
    services = Set("wallet", "players", "game-provider", "rewards", "engagement", "reporting"),
    topics = Set("casino.rounds", "casino.rewards", "platform.audit"),
    routes = Set(("game-provider", "POST", "/v1/casino/brands/{brand}/rounds")),
    keys = Set("casino-brand", "game-provider-mode", "st8-api-key"),
    productModules = Set("casino", "engagement", "reporting", "support", "partner", "predictions"),
    sharedModules = Set("pool")
  )

  private def path(name: String): Path = Path.of(getClass.getResource(s"/manifests/$name.json").toURI)

  private def refusals(name: String): Vector[Refusal] =
    ModuleManifest.validate(path(name), view).fold(identity, _ => Vector.empty)

  private def refusedNaming(name: String, at: String, words: String)(using munit.Location): Unit =
    val found = refusals(name)
    assert(found.nonEmpty, s"$name was accepted")
    assert(found.exists(r => r.path == at && r.message.contains(words)), found.mkString("\n"))

  test("a manifest the contract fully defines is accepted") {
    val m = ModuleManifest.validate(path("valid-casino"), view)
    assertEquals(m.left.toOption, None)
    assertEquals(m.toOption.get.serviceNames, Vector("game-provider", "rewards"))
    assertEquals(m.toOption.get.secretKeys, Set("st8-api-key"))
  }

  test("a service the contract does not list is refused, naming it") {
    refusedNaming("unknown-service", "services[1].name", "service 'jackpots' is not in the contract")
  }

  test("a topic the contract does not list is refused, naming it") {
    refusedNaming("unknown-topic", "topics[1].name", "topic 'casino.jackpots' is not in the contract")
  }

  test("a grant target the contract does not list is refused, naming it") {
    refusedNaming("unknown-grant", "grants.made[0].target", "'route POST game-provider/v1/casino/brands/{brand}/jackpots' is not in the contract")
  }

  test("a value where a key is expected is refused, naming the key") {
    refusedNaming("value-for-key", "configuration[0]", "a value where a key is expected: 'game-provider-mode'")
  }

  test("a literal that looks like a secret is refused, naming where it is") {
    refusedNaming("secret-literal", "services[0].descriptor.env[3].value", "looks like a secret")
  }

  test("a product module depending on another product module is refused (FR-002); partner on casino is the exception") {
    refusedNaming("product-on-product", "dependencies[1].module", "a product module depends on product module 'engagement'")
    assert(ModuleManifest.ProductOnProduct.contains("partner" -> "casino"))
  }

  test("a grant to a module of the same project is refused: it is not a grant (FR-007)") {
    refusedNaming("grant-within-project", "grants.made[1].principal", "a grant within the brand project is not a grant")
  }

  test("one service, one module: a service in two manifests is refused naming both") {
    val two = Vector("valid-casino", "service-in-two-modules").map(n => ModuleManifest.load(path(n)).toOption.get)
    assertEquals(ModuleManifest.across(two).map(_.message), Vector("service 'rewards' is in modules casino and reporting"))
  }

  test("an unknown field is refused by the vendor's schema, naming it") {
    val text = scala.io.Source.fromFile(path("valid-casino").toFile).mkString.replaceFirst("\\{", "{\"schema\": \"2\",")
    val refused = ModuleManifest.parse(text).left.toOption.getOrElse(Vector.empty)
    assert(refused.exists(_.toString.contains("schema")), refused.mkString("\n"))
  }

  test("the vendor's patterns: a key-named field with a long base64 run looks like a secret; a URL does not") {
    assert(ModuleManifest.looksLikeSecret("API_KEY", "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"))
    assert(!ModuleManifest.looksLikeSecret("KEYCLOAK_JWKS_BASE", "http://casino-keycloak.casino-identity.svc"))
    assert(!ModuleManifest.looksLikeSecret("image", "europe-west1-docker.pkg.dev/eitheror/casino/game-provider:0.0.0"))
  }
