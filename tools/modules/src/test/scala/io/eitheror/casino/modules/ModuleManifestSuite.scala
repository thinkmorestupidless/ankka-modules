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

  // ── the tree's own manifests (spec 021 T011) ───────────────────────────────

  private lazy val tree = ContractView.fromTree(Fixtures.root)

  test("the five manifests under modules/ validate against the tree's contract, and one service is in one module") {
    val (valid, refused) = ModuleManifest.validateTree(Fixtures.root, tree)
    assertEquals(refused.map((p, rs) => s"$p: ${rs.mkString("; ")}"), Vector.empty)
    assertEquals(valid.map(_.name), Vector("core", "casino", "engagement", "reporting", "support"))
  }

  test("every service a manifest names has a build under services/ or is listed as not yet built in contract/keys.md") {
    val keys  = Keys.load(Fixtures.root)
    val built = Keys.built(Fixtures.root)
    val named = ModuleManifest.validateTree(Fixtures.root, tree)._1.flatMap(_.serviceNames)
    named.foreach(s => assert(built.contains(s) || keys.notYetBuilt.contains(s), s"$s has no build and is not listed as not yet built"))
    assert(keys.notYetBuilt.keySet.intersect(built).isEmpty, s"listed as not yet built but built: ${keys.notYetBuilt.keySet.intersect(built)}")
    assertEquals(named.filter(built.contains).toSet, Set("wallet", "game-provider", "players", "responsible-gambling", "attribution", "kyc"))
  }

  test("in the tree an image is the registry's, named for its service, at 0.0.0 until the release renders the tag") {
    ModuleManifest.validateTree(Fixtures.root, tree)._1.flatMap(_.services).foreach { s =>
      assertEquals(s.image, s"europe-west1-docker.pkg.dev/eitheror/casino/${s.name}:0.0.0", s.name)
    }
  }

  test("the core declares the real-money requirement on the installation, conditional on casino-real-money (FR-010, R6)") {
    val core = ModuleManifest.validateTree(Fixtures.root, tree)._1.find(_.name == "core").get
    assertEquals(core.requires.size, 1)
    assertEquals(core.requires.head.when, Some("casino-real-money" -> "true"))
    assertEquals(core.requires.head.facts.map(_._1).toSet, Set(
      "backups.target", "backups.copyRequired", "backups.sharesFailureDomain", "erasure.enabled", "erasure.keyringInstances",
      "broker.nodes", "broker.defaultCopies", "broker.minInSync", "topics.longestRetention"))
  }

  test("a key a service names must be one its module lists in configuration") {
    val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(path("valid-casino").toFile).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    node.get("configuration").get(0).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("key", "st8-operator-id")
    val m = ModuleManifest.parse(node.toString).toOption.get
    val refused = ModuleManifest.rules(m, view.copy(keys = view.keys + "st8-operator-id"))
    assert(refused.exists(r => r.path == "services[0].descriptor.env[1].key" && r.message.contains("'game-provider-mode' is not one this module names")), refused.mkString("\n"))
  }

  test("each broken variant is refused by the tree's contract too, naming the entry") {
    for (name, at) <- Vector("unknown-service" -> "services[1].name", "unknown-topic" -> "topics[1].name", "unknown-grant" -> "grants.made[0].target",
        "value-for-key" -> "configuration[0]", "secret-literal" -> "services[0].descriptor.env[3].value", "product-on-product" -> "dependencies[1].module")
    do
      val found = ModuleManifest.validate(path(name), tree).fold(identity, _ => Vector.empty)
      assert(found.exists(_.path == at), s"$name: ${found.mkString("; ")}")
  }

