package io.eitheror.casino.support

/** The settings from the environment (T006): every one read, and a missing or unreadable one refused naming it. */
class SettingsSuite extends munit.FunSuite:
  private val base = Map("CASINO_BRAND" -> "goldvibe", "ANKKA_AUTH_ISSUERS" -> "player,staff", "MODEL_PROVIDER_KEY" -> "sk-test")

  private def refused(env: Map[String, String]): String = Settings.fromEnvironment(env).left.getOrElse(fail("accepted"))

  test("the module's environment gives every setting") {
    val s = Settings.fromEnvironment(base ++ Map("CASINO_PROJECT" -> "goldvibe-prod", "CASINO_REAL_MONEY" -> "true",
      "AGENT_MODEL_PROVIDER" -> "anthropic", "AGENT_COMPACTION_BYTES" -> "50000", "AGENT_COMPACTION_RECENT" -> "6",
      "AGENT_JUDGE" -> "jev")).fold(fail(_), identity)
    assertEquals((s.brand, s.project, s.realMoney, s.compactionBytes, s.compactionRecent, s.judge),
      ("goldvibe", "goldvibe-prod", true, 50000, 6, Some("jev")))
    assertEquals(s.model, ModelSource.Anthropic(Settings.Secret("sk-test")))
    assertEquals(s.issuers, Set("player", "staff"))
  }

  test("left out, the project is the brand, no real money, compaction at 100 000 and 10, and no judge") {
    val s = Settings.fromEnvironment(base).fold(fail(_), identity)
    assertEquals((s.project, s.realMoney, s.compactionBytes, s.compactionRecent, s.judge), ("goldvibe", false, 100000, 10, None))
  }

  test("the judge the module leaves at none is no judge") {
    assertEquals(Settings.fromEnvironment(base + ("AGENT_JUDGE" -> "none")).map(_.judge), Right(None))
  }

  test("a scripted model needs no key") {
    val s = Settings.fromEnvironment(base - "MODEL_PROVIDER_KEY" + ("AGENT_MODEL_PROVIDER" -> "scripted:/tmp/turns.json"))
    assertEquals(s.map(_.model), Right(ModelSource.Scripted("/tmp/turns.json")))
  }

  test("each missing or unreadable setting is refused naming its variable") {
    assert(refused(base - "CASINO_BRAND").startsWith("CASINO_BRAND"))
    assert(refused(base - "MODEL_PROVIDER_KEY").startsWith("MODEL_PROVIDER_KEY"))
    assert(refused(base - "ANKKA_AUTH_ISSUERS").startsWith("ANKKA_AUTH_ISSUERS"))
    assert(refused(base + ("ANKKA_AUTH_ISSUERS" -> "player")).contains("staff"))
    assert(refused(base + ("CASINO_REAL_MONEY" -> "yes")).startsWith("CASINO_REAL_MONEY"))
    assert(refused(base + ("AGENT_COMPACTION_BYTES" -> "0")).startsWith("AGENT_COMPACTION_BYTES"))
    assert(refused(base + ("AGENT_COMPACTION_RECENT" -> "ten")).startsWith("AGENT_COMPACTION_RECENT"))
    assert(refused(base + ("AGENT_MODEL_PROVIDER" -> "openai")).startsWith("AGENT_MODEL_PROVIDER"))
    assert(refused(base + ("AGENT_JUDGE" -> "oracle")).startsWith("AGENT_JUDGE"))
  }

  test("the model's key is never shown") {
    val s = Settings.fromEnvironment(base).fold(fail(_), identity)
    assert(!s.toString.contains("sk-test"), s.toString)
  }
