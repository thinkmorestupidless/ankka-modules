package io.eitheror.casino.modules

import io.eitheror.casino.contract.Layout
import io.eitheror.casino.modules.ModuleManifest.Refusal

import java.nio.file.{Files, Path}

/** A brand's values (spec 021 FR-003, contracts/values.md): goldvibe's validate; each broken variant is refused naming the entry. */
class ValuesSuite extends munit.FunSuite:

  private val keys = Keys.load(Fixtures.root)
  private val view = Values.View(keys, Layout.Services.toSet)

  private def variant(name: String): Path = Path.of(getClass.getResource(s"/values/$name.json").toURI)

  /** A variant read as if it were `brands/goldvibe/values.json`, so the directory rule applies. */
  private def refusals(name: String, directory: Option[String] = Some("goldvibe")): Vector[Refusal] =
    Values.parse(Files.readString(variant(name)), view, directory).fold(identity, _ => Vector.empty)

  private def refusedNaming(name: String, at: String, words: String, directory: Option[String] = Some("goldvibe"))(using munit.Location): Unit =
    val found = refusals(name, directory)
    assert(found.nonEmpty, s"$name was accepted")
    assert(found.exists(r => r.path == at && r.message.contains(words)), found.mkString("\n"))

  private lazy val goldvibe = Values.load(Fixtures.root.resolve("brands/goldvibe/values.json"), view)

  test("goldvibe's values validate") {
    assertEquals(goldvibe.left.toOption, None)
    assertEquals(goldvibe.toOption.get.slug, "goldvibe")
  }

  test("a version, a service list or a grant is the module's, not the brand's") {
    refusedNaming("with-version", "version", "'version' is the module's, not the brand's")
    refusedNaming("with-services", "services", "'services' is the module's, not the brand's")
    refusedNaming("with-grants", "grants", "'grants' is the module's, not the brand's")
  }

  test("a secret key in values is refused: it is set with secrets") {
    refusedNaming("secret-key-in-values", "values.st8-api-key", "'st8-api-key' is a secret key: set it with secrets")
  }

  test("a value that looks like a secret is refused, naming it") {
    refusedNaming("secret-looking-value", "values.st8-operator-id", "looks like a secret")
  }

  test("a key no module names is refused") {
    refusedNaming("unknown-key", "values.jackpot-size", "configuration key 'jackpot-size' is not in the contract")
  }

  test("a derived key written by the brand is refused (E1)") {
    refusedNaming("derived-key", "values.casino-brand", "'casino-brand' is derived from")
  }

  test("a hostname for a service no module lists is refused") {
    refusedNaming("unknown-hostname", "hostnames.jackpots", "service 'jackpots' is not in the contract")
  }

  test("a slug not matching its directory is refused") {
    refusedNaming("spinvibe", "brand.slug", "slug 'spinvibe' is in directory 'goldvibe'")
    assertEquals(refusals("spinvibe", Some("spinvibe")), Vector.empty)
  }

  test("the derived keys are what spec 001's tool computed, and the tenant project is the one derivation") {
    val v = goldvibe.toOption.get
    val d = Values.derived(v, Values.tenantProject("goldvibe", None), Some(Fixtures.devKind))
    assertEquals(d("casino-brand"), "goldvibe")
    assertEquals(d("casino-project"), "goldvibe")
    assertEquals(d("casino-real-money"), "false")
    assertEquals(d("auth-realm"), "casino-goldvibe")
    assertEquals(d("auth-player-issuer"), "https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-goldvibe")
    assertEquals(d("auth-staff-jwks-url"), "http://casino-keycloak.casino-identity.svc/realms/eitheror/protocol/openid-connect/certs")
    assertEquals(d.keySet, keys.derived.map(_.key).toSet, "every key contract/keys.md marks derived, and no other")
    assertEquals(Values.tenantProject("goldvibe", Some("goldvibe-ltd")), "goldvibe-ltd-vendor")
  }

  test("every secret key of core and casino is covered by goldvibe's secrets; one not declared is named") {
    val v = goldvibe.toOption.get
    assertEquals(Values.uncovered(v, keys, Set("players-realm-admin", "st8-api-key")), Vector.empty)
    assertEquals(Values.uncovered(v, keys, Set("push-provider-key")).map(_.message), Vector("secret key 'push-provider-key' has no secret"))
  }
