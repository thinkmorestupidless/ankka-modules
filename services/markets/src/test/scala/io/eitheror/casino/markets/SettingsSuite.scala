package io.eitheror.casino.markets

class SettingsSuite extends munit.FunSuite:

  private val env = Map(
    "CASINO_BRAND" -> "goldvibe", "PREDICTIONS_CATEGORIES" -> "politics, sports", "PREDICTIONS_CURRENCIES" -> "EUR,USD",
    "PREDICTIONS_MAX_ORDER_COST" -> "100000", "PREDICTIONS_MAX_POSITION" -> "10000"
  )

  test("the brand's values, with no taker fee when the values name none") {
    assertEquals(Settings.fromEnvironment(env), Right(Settings("goldvibe", Vector("politics", "sports"), Vector("EUR", "USD"), 100000, 10000, 0)))
    assertEquals(Settings.fromEnvironment(env + ("PREDICTIONS_TAKER_FEE_BPS" -> "700")).map(_.takerFeeBps), Right(700))
  }

  test("a value that cannot be read is refused naming its variable") {
    val refused = Vector(
      env - "PREDICTIONS_CATEGORIES"                     -> "PREDICTIONS_CATEGORIES",
      env.updated("PREDICTIONS_CATEGORIES", " , ")       -> "PREDICTIONS_CATEGORIES",
      env.updated("PREDICTIONS_CURRENCIES", "euro")      -> "PREDICTIONS_CURRENCIES",
      env.updated("PREDICTIONS_MAX_ORDER_COST", "0")     -> "PREDICTIONS_MAX_ORDER_COST",
      env.updated("PREDICTIONS_MAX_POSITION", "lots")    -> "PREDICTIONS_MAX_POSITION",
      env.updated("PREDICTIONS_TAKER_FEE_BPS", "10001")  -> "PREDICTIONS_TAKER_FEE_BPS",
      env - "CASINO_BRAND"                               -> "CASINO_BRAND"
    )
    refused.foreach { (e, name) =>
      val r = Settings.fromEnvironment(e)
      assert(r.left.exists(_.startsWith(name)), s"$name: $r")
    }
  }
