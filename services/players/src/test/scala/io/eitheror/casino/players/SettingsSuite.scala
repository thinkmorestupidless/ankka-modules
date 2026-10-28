package io.eitheror.casino.players

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.Duration

/** Research R17: every setting's default, a value that cannot be read refused by its variable, and the secrets by name. */
class SettingsSuite extends munit.FunSuite with LogCapturing:

  private val brand = Map("CASINO_BRAND" -> "goldvibe")

  test("every default is research R17's") {
    val s = Settings.fromEnvironment(brand).toOption.get
    assertEquals(s, Settings(brand = "goldvibe"))
    assertEquals(s.realmReconcileInterval, Duration.ofSeconds(60))
    assertEquals(s.verificationValidity, Duration.ofHours(24))
    assertEquals(s.verificationResendInterval, Duration.ofMinutes(5))
    assertEquals(s.mayLogInBound, Duration.ofMillis(500))
    assertEquals(s.realmAnswerBound, Duration.ofMillis(80))
    assertEquals(s.sessionEndBound, Duration.ofSeconds(5))
    assertEquals(s.exportPartBound, Duration.ofSeconds(10))
    assertEquals(s.exportRetention, Duration.ofHours(24))
    assertEquals(s.registrationBound, Duration.ofSeconds(5))
    assertEquals(s.realm, "casino-goldvibe")
    assert(!s.realMoney)
  }

  test("each variable is read") {
    val s = Settings.fromEnvironment(brand ++ Map("CASINO_REAL_MONEY" -> "true", "KEYCLOAK_URL" -> "http://kc:8080/",
      "PLAYER_SITE_BASE" -> "https://goldvibe.example", "INGEST_URL" -> "http://ingest", "REALM_RECONCILE_INTERVAL" -> "1s",
      "VERIFICATION_VALIDITY" -> "1h", "VERIFICATION_RESEND_INTERVAL" -> "1m", "MAY_LOG_IN_BOUND" -> "100ms",
      "REALM_ANSWER_BOUND" -> "50ms", "SESSION_END_BOUND" -> "2s", "EXPORT_PART_BOUND" -> "3s", "EXPORT_RETENTION" -> "2h",
      "REGISTRATION_BOUND" -> "4s", "GEO_URL" -> "http://geo/")).toOption.get
    assertEquals(s, Settings("goldvibe", true, "http://kc:8080", "https://goldvibe.example", Some("http://ingest"), "http://geo",
      Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofMillis(100), Duration.ofMillis(50),
      Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofHours(2), Duration.ofSeconds(4)))
  }

  test("no brand is refused naming CASINO_BRAND") {
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.startsWith("CASINO_BRAND")))
  }

  test("a malformed duration or flag is refused naming the variable") {
    Seq("REALM_RECONCILE_INTERVAL" -> "soon", "VERIFICATION_VALIDITY" -> "0h", "MAY_LOG_IN_BOUND" -> "-1ms",
      "SESSION_END_BOUND" -> "5", "EXPORT_RETENTION" -> "a day", "CASINO_REAL_MONEY" -> "yes").foreach { (name, value) =>
      val refused = Settings.fromEnvironment(brand + (name -> value))
      assert(refused.left.exists(_.startsWith(name)), s"$name=$value: $refused")
    }
  }

  test("a secret is read from the store first, else from its variable, and refused naming both when neither holds it") {
    val store = com.thinkmorestupidless.ankka.testkit.InMemorySecretStore()
    store.put(Secrets.EmailToken, "from-store")
    assertEquals(Secrets.read(Secrets.EmailToken, Some(store), Map("PLAYERS_EMAIL_TOKEN" -> "from-env")), Right("from-store"))
    assertEquals(Secrets.read(Secrets.LookupKey, Some(store), Map("PLAYERS_LOOKUP_KEY" -> "from-env")), Right("from-env"))
    assertEquals(Secrets.read(Secrets.LookupKey, None, Map("PLAYERS_LOOKUP_KEY" -> "from-env")), Right("from-env"))
    val refused = Secrets.read(Secrets.RealmAdmin, Some(store), Map.empty)
    assert(refused.left.exists(m => m.startsWith("players-realm-admin") && m.contains("PLAYERS_REALM_ADMIN")), refused.toString)
    assertEquals(Secrets.all.map(Secrets.variable), Seq("PLAYERS_REALM_ADMIN", "PLAYERS_WEBHOOK_SECRET", "PLAYERS_REALM_SECRET",
      "PLAYERS_EMAIL_TOKEN", "PLAYERS_LOOKUP_KEY", "PLAYERS_MERCHANT_KEY"))
  }
