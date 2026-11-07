package io.eitheror.casino.provider.edge

import io.eitheror.casino.provider.core.Schedule
import io.eitheror.casino.provider.st8.St8Protocol

import java.net.URI
import scala.concurrent.duration.*

/** The provider's settings as `shared/games/manifest.json` writes them (`contract/keys.md`). */
class SettingsSuite extends munit.FunSuite:

  private val base = Map("PROVIDER_EXTERNAL_URL" -> "https://provider-games.127.0.0.1.sslip.io:8443")

  test("only the external address is required; retention is 90 days and the schedule the provider's default") {
    val s = Settings.fromEnvironment(base).fold(fail(_), identity)
    assertEquals(s.externalUrl, URI("https://provider-games.127.0.0.1.sslip.io:8443"))
    assertEquals(s.retentionDays, 90)
    assertEquals(s.schedule, Schedule.providerDefault(5.seconds, St8Protocol.defaultRetryOn))
    assertEquals(s.localCa, None)
    assertEquals(s.staffIssuer, None)
  }

  test("the manifest's own values are read as written") {
    val manifest = java.nio.file.Files.readString(java.nio.file.Path.of("../../shared/games/manifest.json"))
    val schedule = """"PROVIDER_RETRY_SCHEDULE": "(.*)"""".r.findFirstMatchIn(manifest).get.group(1).replace("\\\"", "\"")
    val s = Settings.fromEnvironment(base ++ Map(
      "PROVIDER_RETENTION_DAYS" -> "30",
      "PROVIDER_RETRY_SCHEDULE" -> schedule,
      "PROVIDER_LOCAL_CA"       -> "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----",
      "ANKKA_AUTH_STAFF_ISSUER" -> "https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror"
    )).fold(fail(_), identity)
    assertEquals(s.retentionDays, 30)
    assertEquals(s.schedule.cap, 8.hours)
    assert(s.localCa.isDefined)
    assertEquals(s.staffIssuer.map(_.getPath), Some("/realms/eitheror"))
  }

  test("a malformed setting is refused naming it") {
    val cases = Vector(
      Map.empty[String, String]                                      -> "PROVIDER_EXTERNAL_URL is required",
      Map("PROVIDER_EXTERNAL_URL" -> "provider-games")               -> "PROVIDER_EXTERNAL_URL 'provider-games'",
      base + ("PROVIDER_RETENTION_DAYS" -> "0")                      -> "PROVIDER_RETENTION_DAYS '0'",
      base + ("PROVIDER_RETENTION_DAYS" -> "ninety")                 -> "PROVIDER_RETENTION_DAYS 'ninety'",
      base + ("PROVIDER_RETRY_SCHEDULE" -> """{"intervals":[]}""")   -> "PROVIDER_RETRY_SCHEDULE: schedule.cap is required",
      base + ("PROVIDER_LOCAL_CA" -> "not a certificate")            -> "PROVIDER_LOCAL_CA",
      base + ("ANKKA_AUTH_STAFF_ISSUER" -> "eitheror")               -> "ANKKA_AUTH_STAFF_ISSUER 'eitheror'"
    )
    cases.foreach { (env, expected) =>
      Settings.fromEnvironment(env) match
        case Left(refusal) => assert(refusal.contains(expected), s"$env: $refusal")
        case Right(s)      => fail(s"accepted $env as $s")
    }
  }
