package io.eitheror.casino.audit

import java.time.{Duration, Instant}

class SettingsSuite extends munit.FunSuite:

  test("the defaults: seven years, generation 1 from the earliest, five minutes of skew, pages of 1000") {
    val s = Settings.fromEnvironment(Map.empty).toOption.get
    assertEquals((s.retentionYears, s.readGeneration, s.readFrom, s.skewBound, s.pageLimit, s.brands),
      (7, 1, None, Duration.ofMinutes(5), 1000, Vector.empty))
    assertEquals(s.project, "reasoner")
  }

  test("every setting read from the environment") {
    val s = Settings.fromEnvironment(Map(
      "AUDIT_BRANDS" -> "goldvibe, spinvibe,goldvibe", "AUDIT_RETENTION_YEARS" -> "10", "AUDIT_READ_GENERATION" -> "2",
      "AUDIT_READ_FROM" -> "2026-11-01T00:00:00Z", "AUDIT_SKEW_BOUND" -> "30s", "AUDIT_PAGE_LIMIT" -> "500",
      "KEYCLOAK_URL" -> "https://auth.example/", "KEYCLOAK_ADMIN_USERNAME" -> "admin"
    )).toOption.get
    assertEquals(s.brands, Vector("goldvibe", "spinvibe"))
    assertEquals((s.retentionYears, s.readGeneration, s.readFrom), (10, 2, Some(Instant.parse("2026-11-01T00:00:00Z"))))
    assertEquals((s.skewBound, s.pageLimit, s.keycloakUrl, s.keycloakAdminUsername),
      (Duration.ofSeconds(30), 500, Some("https://auth.example"), Some("admin")))
  }

  test("a generation above 1 without the restore point is refused, naming AUDIT_READ_FROM") {
    val refused = Settings.fromEnvironment(Map("AUDIT_READ_GENERATION" -> "2"))
    assert(refused.left.exists(_.startsWith("AUDIT_READ_FROM: required")), refused)
  }

  test("each unreadable setting is refused naming its variable") {
    Map(
      "AUDIT_BRANDS" -> "Gold Vibe", "AUDIT_RETENTION_YEARS" -> "0", "AUDIT_READ_GENERATION" -> "one",
      "AUDIT_READ_FROM" -> "yesterday", "AUDIT_SKEW_BOUND" -> "5 minutes", "AUDIT_PAGE_LIMIT" -> "-1",
      "KEYCLOAK_URL" -> "ftp://auth"
    ).foreach { (name, value) =>
      val refused = Settings.fromEnvironment(Map(name -> value))
      assert(refused.left.exists(_.startsWith(s"$name:")), s"$name=$value: $refused")
    }
  }
