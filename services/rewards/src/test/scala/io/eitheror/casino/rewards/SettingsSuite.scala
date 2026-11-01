package io.eitheror.casino.rewards

class SettingsSuite extends munit.FunSuite:
  test("the brand is required, naming the variable") {
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.startsWith("CASINO_BRAND")))
  }

  test("no code document when the URL is absent, empty or none") {
    for v <- Seq(None, Some(""), Some("none")) do
      val env = Map("CASINO_BRAND" -> "goldvibe") ++ v.map("REWARDS_CODE_DOCUMENT_URL" -> _)
      assertEquals(Settings.fromEnvironment(env).map(_.codeDocumentUrl), Right(None))
  }

  test("a code document URL is read, and one that is not http(s) refused") {
    val ok = Map("CASINO_BRAND" -> "goldvibe", "REWARDS_CODE_DOCUMENT_URL" -> "https://programme.example/codes.json")
    assertEquals(Settings.fromEnvironment(ok).map(_.codeDocumentUrl), Right(Some("https://programme.example/codes.json")))
    val bad = ok.updated("REWARDS_CODE_DOCUMENT_URL", "ftp://x")
    assert(Settings.fromEnvironment(bad).left.exists(_.startsWith("REWARDS_CODE_DOCUMENT_URL")))
  }

  test("the waits are the original's and research R13's") {
    val s = Settings()
    assertEquals((s.pollEvery.toSeconds, s.pollLimit, s.codesEvery.toMinutes, s.codesStaleAfter.toMinutes, s.requestBound.toSeconds),
      (30L, 50, 5L, 15L, 2L))
  }
