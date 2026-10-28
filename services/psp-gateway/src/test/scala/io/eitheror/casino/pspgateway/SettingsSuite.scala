package io.eitheror.casino.pspgateway

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.providers.{Credential, Operation}
import io.eitheror.casino.pspgateway.sessions.Provider

import java.time.Duration

/** Data-model.md *Settings*, FR-020: nothing reaches a provider the installation did not name. */
class SettingsSuite extends munit.FunSuite with LogCapturing:

  private val base = Map("PUBLIC_URL" -> "https://pay.example")

  test("PUBLIC_URL is required") {
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.contains("PUBLIC_URL")))
    assert(Settings.fromEnvironment(Map("PUBLIC_URL" -> " ")).isLeft)
  }

  test("the defaults: 20 s per provider call, a 2 m re-drive, merchant by name") {
    val s = Settings.fromEnvironment(base).toOption.get
    assertEquals(s.providerTimeout, Duration.ofSeconds(20))
    assertEquals(s.redriveInterval, Duration.ofMinutes(2))
    assertEquals(s.merchantService, "merchant")
  }

  test("no default reaches a host: with no base URLs, every provider is unconfigured") {
    val s = Settings.fromEnvironment(base).toOption.get
    assertEquals(s.baseUrls, Map.empty[String, String])
    Provider.values.foreach { p =>
      assertEquals(s.baseUrl(p, Operation.Deposit, Credential.empty), Left(ProviderNotConfigured(p)))
      assert(!s.configured(p), p)
    }
  }

  test("a missing base URL names the provider") {
    val s = Settings.fromEnvironment(base + ("T365_BASE_URL" -> "http://t365/")).toOption.get
    assertEquals(s.baseUrl(Provider.T365, Operation.Deposit, Credential.empty), Right("http://t365"))
    assertEquals(s.baseUrl(Provider.Cozeni, Operation.Deposit, Credential.empty), Left(ProviderNotConfigured(Provider.Cozeni)))
  }

  test("Digitarium's production URL is chosen by the credential's environment") {
    val s = Settings.fromEnvironment(base + ("DIGITARIUM_PRODUCTION_URL" -> "http://prod")).toOption.get
    assertEquals(s.baseUrl(Provider.Digitarium, Operation.Deposit, Credential(Map("environment" -> "production"))), Right("http://prod"))
    assert(s.baseUrl(Provider.Digitarium, Operation.Deposit, Credential.empty).isLeft, "the sandbox is not named")
    assert(s.configured(Provider.Digitarium))
  }

  test("a malformed duration is refused naming the variable") {
    assert(Settings.fromEnvironment(base + ("PROVIDER_TIMEOUT" -> "soon")).left.exists(_.startsWith("PROVIDER_TIMEOUT")))
    assert(Settings.fromEnvironment(base + ("REDRIVE_INTERVAL" -> "0s")).left.exists(_.startsWith("REDRIVE_INTERVAL")))
    assertEquals(Settings.fromEnvironment(base + ("REDRIVE_INTERVAL" -> "30s")).map(_.redriveInterval), Right(Duration.ofSeconds(30)))
  }

  test("an address on the service joins without a doubled slash") {
    assertEquals(Settings("https://pay.example/").public("/v1/webhooks/t365"), "https://pay.example/v1/webhooks/t365")
  }
