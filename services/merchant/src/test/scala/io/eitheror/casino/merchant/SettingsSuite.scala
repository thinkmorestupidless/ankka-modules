package io.eitheror.casino.merchant

import java.time.Duration

/** Research R19: the settings' defaults, and a malformed one refused naming its variable. */
class SettingsSuite extends munit.FunSuite:

  test("the defaults are the original's and the plan's") {
    val s = Settings.fromEnvironment(Map.empty).toOption.get
    assertEquals(s.paymentDeadline, Duration.ofMinutes(30))
    assertEquals(s.uncreditedBound, Duration.ofMinutes(15))
    assertEquals(s.customerRetention, Duration.ofDays(90))
    assertEquals(s.operatorSyncInterval, Duration.ofSeconds(60))
    assertEquals(s.operatorsRealm, "eitheror")
    assertEquals(s.brands, Vector.empty)
  }

  test("durations are read as the manifest writes them") {
    val s = Settings.fromEnvironment(Map("PAYMENT_DEADLINE" -> "2s", "UNCREDITED_BOUND" -> "500ms", "CUSTOMER_RETENTION" -> "7d")).toOption.get
    assertEquals(s.paymentDeadline, Duration.ofSeconds(2))
    assertEquals(s.uncreditedBound, Duration.ofMillis(500))
    assertEquals(s.customerRetention, Duration.ofDays(7))
    assertEquals(Settings.render(Duration.ofMinutes(15)), "15m")
  }

  test("a malformed duration is refused naming the variable") {
    val refused = Settings.fromEnvironment(Map("UNCREDITED_BOUND" -> "fifteen"))
    assert(refused.left.exists(_.startsWith("UNCREDITED_BOUND")), refused.toString)
  }

  test("the brands to seed are a JSON list of slug and name") {
    assertEquals(Settings.brandsFrom("""[{"slug":"goldvibe","name":"Goldvibe"},{"slug":"spinvibe"}]"""),
      Right(Vector(SeededBrand("goldvibe", "Goldvibe"), SeededBrand("spinvibe", "spinvibe"))))
    assert(Settings.brandsFrom("""{"slug":"goldvibe"}""").isLeft)
    assert(Settings.brandsFrom("""[{"name":"no slug"}]""").isLeft)
  }
