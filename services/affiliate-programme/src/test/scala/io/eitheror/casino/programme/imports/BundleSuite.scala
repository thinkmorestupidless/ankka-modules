package io.eitheror.casino.programme.imports

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}

import java.time.Instant

class BundleSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-07-01T00:00:00Z")
  private def account(id: String, status: String = "active", plan: Option[String] = Some("Std")) =
    BundleAccount(id, s"$id@x.example", id, createdAt = at, membership = BundleMembership(status, plan, appliedAt = at))
  private val ok = Bundle(brand = "spinvibe", exportedAt = at, plans = Vector(BundlePlan("Std", "cpa", createdAt = at)),
    accounts = Vector(account("a"), account("b", "rejected", None)),
    facts = Vector(BundleFact("f", "a", "EUR", "cpa", 100, at, at, "2026-07")))

  test("a bundle of the brand, its plans named and its kinds and statuses the programme's, has no problem") {
    assertEquals(Bundle.problem(ok, "spinvibe"), None)
  }

  test("each problem is named before anything is written") {
    assert(Bundle.problem(ok, "goldvibe").exists(_.contains("spinvibe")))
    assert(Bundle.problem(ok.copy(version = 2), "spinvibe").exists(_.startsWith("version")))
    assert(Bundle.problem(ok.copy(accounts = ok.accounts :+ account("a")), "spinvibe").exists(_.contains("accounts")))
    assert(Bundle.problem(ok.copy(accounts = Vector(account("c", plan = Some("Gold")))), "spinvibe").exists(_.contains("Gold")))
    assert(Bundle.problem(ok.copy(accounts = Vector(account("c", "terminated"))), "spinvibe").exists(_.contains("terminated")))
    assert(Bundle.problem(ok.copy(facts = Vector(ok.facts.head.copy(kind = "GGR"))), "spinvibe").exists(_.contains("GGR")))
    assert(Bundle.problem(ok.copy(payouts = Vector(BundlePayout("a", "EUR", "2026-07", "open", createdAt = at))), "spinvibe")
      .exists(_.contains("open")))
  }

  test("the wire form reads back as written, absent fields at their defaults") {
    assertEquals(readFromString[Bundle](writeToString(ok)), ok)
    val least = readFromString[Bundle]("""{"version":1,"brand":"spinvibe","exportedAt":"2026-07-01T00:00:00Z"}""")
    assertEquals((least.accounts, least.settings), (Vector.empty, None))
  }
