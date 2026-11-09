package io.eitheror.casino.engagement

class SettingsSuite extends munit.FunSuite:
  private val brand = Map("CASINO_BRAND" -> "goldvibe")

  test("the brand is required, naming the variable") {
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.startsWith("CASINO_BRAND")))
  }

  test("the CRM's hosts are the region's unless overridden, and an override must be http(s)") {
    assertEquals(Settings.fromEnvironment(brand).map(s => (s.trackUrl, s.appUrl)), Right((None, None)))
    val stub = brand ++ Map("CRM_TRACK_URL" -> "http://127.0.0.1:18201/", "CRM_APP_URL" -> "http://127.0.0.1:18202")
    assertEquals(Settings.fromEnvironment(stub).map(s => (s.trackUrl, s.appUrl)),
      Right((Some("http://127.0.0.1:18201"), Some("http://127.0.0.1:18202"))))
    assert(Settings.fromEnvironment(brand.updated("CRM_APP_URL", "ftp://x")).left.exists(_.startsWith("CRM_APP_URL")))
  }

  test("the timeout, staleness and connection bound are read, and refused when not positive") {
    val env = brand ++ Map("CRM_TIMEOUT" -> "3", "STALENESS_HOURS" -> "48", "MAX_CONNECTIONS" -> "12")
    assertEquals(Settings.fromEnvironment(env).map(s => (s.crmTimeout.toSeconds, s.staleness.toHours, s.maxConnections)),
      Right((3L, 48L, 12)))
    for name <- Seq("CRM_TIMEOUT", "STALENESS_HOURS", "MAX_CONNECTIONS") do
      assert(Settings.fromEnvironment(brand.updated(name, "0")).left.exists(_.startsWith(name)), name)
  }

  test("the instance is the pod's host name") {
    assertEquals(Settings.fromEnvironment(brand.updated("HOSTNAME", "engagement-7c9")).map(_.instanceId), Right("engagement-7c9"))
    assertEquals(Settings.fromEnvironment(brand).map(_.instanceId), Right("local"))
  }

  test("the waits are research R5's and R10's") {
    val s = Settings()
    assertEquals((s.crmTimeout.toSeconds, s.staleness.toHours, s.maxConnections, s.attemptBase.toMillis, s.attempts),
      (10L, 24L, 10000, 500L, 4))
    assertEquals((s.heartbeatEvery.toSeconds, s.revalidateEvery.toSeconds, s.recycleAfter.toMinutes, s.sweepEvery.toSeconds,
      s.leaseExpiry.toSeconds, s.ticketTtl.toSeconds), (15L, 30L, 5L, 30L, 90L, 30L))
  }
