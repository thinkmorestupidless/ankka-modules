package io.eitheror.casino.exchange

class SettingsSuite extends munit.FunSuite:

  test("both gates when the values name none, and only those named when they do") {
    assertEquals(Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe")), Right(Settings("goldvibe", Set(Gate.PlayGate, Gate.MayBet))))
    assertEquals(Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe", "PREDICTIONS_GATES" -> "may-bet")).map(_.gates), Right(Set(Gate.MayBet)))
    assertEquals(Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe", "PREDICTIONS_GATES" -> "")).map(_.gates), Right(Set.empty[Gate]))
  }

  test("a gate that is not one is refused naming it, and a missing brand is refused") {
    assert(Settings.fromEnvironment(Map("CASINO_BRAND" -> "goldvibe", "PREDICTIONS_GATES" -> "limits")).left.exists(_.contains("'limits'")))
    assert(Settings.fromEnvironment(Map.empty).left.exists(_.startsWith("CASINO_BRAND")))
  }
