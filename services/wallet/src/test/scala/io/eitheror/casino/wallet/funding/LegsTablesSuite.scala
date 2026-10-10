package io.eitheror.casino.wallet.funding

/** The original's `legs_test.go`, case for case: the breakdown and contribution lists. */
class LegsTablesSuite extends munit.FunSuite:

  private val parseCases: Seq[(String, String, Option[Vector[Leg]])] = Seq(
    ("empty", "", Some(Vector.empty)),
    ("one, no trailing pipe", "rf1:500", Some(Vector(Leg("rf1", 500)))),
    ("one, trailing pipe", "rf1:500|", Some(Vector(Leg("rf1", 500)))),
    ("two", "rf1:500|rf2:250|", Some(Vector(Leg("rf1", 500), Leg("rf2", 250)))),
    ("negative amount", "rf1:-100|", Some(Vector(Leg("rf1", -100)))),
    ("missing colon", "rf1500", None),
    ("empty record id", ":500", None),
    ("non-integer amount", "rf1:abc", None),
    ("decimal amount rejected", "rf1:5.00", None)
  )

  for (name, in, want) <- parseCases do
    test(s"legs_test.go/TestParseBreakdown/$name") {
      val got = Resolver.parseLegList(in)
      want match
        case Some(legs) => assertEquals(got, Right(legs))
        case None       => assert(got.isLeft, s"parseLegList($in): want error, got $got")
    }

  private val formatCases: Seq[(String, Legs, String)] = Seq(
    ("empty", Legs(), ""),
    ("real leg only, ignored", Legs(real = 500), ""),
    ("one record leg", Legs(records = Vector(Leg("rf1", 500))), "rf1:500|"),
    ("two record legs, order preserved", Legs(records = Vector(Leg("rf1", 500), Leg("rf2", 250))), "rf1:500|rf2:250|")
  )

  for (name, in, want) <- formatCases do
    test(s"legs_test.go/TestFormatBreakdown/$name") {
      assertEquals(Resolver.formatLegList(in.records), want)
    }

  test("legs_test.go/TestBreakdownRoundTrip") {
    Seq(Vector.empty[Leg], Vector(Leg("rf1", 500)), Vector(Leg("rf1", 500), Leg("rf2", 250), Leg("rf3", 1))).foreach { legs =>
      val formatted = Resolver.formatLegList(legs)
      assertEquals(Resolver.parseLegList(formatted), Right(legs), s"via $formatted")
    }
  }

  test("legs_test.go/TestParseFormatContribs") {
    val c = Resolver.parseLegList("rf1:100|rf2:50|")
    assertEquals(c, Right(Vector(Leg("rf1", 100), Leg("rf2", 50))))
    assertEquals(Resolver.formatLegList(c.toOption.get), "rf1:100|rf2:50|")
    assert(Resolver.parseLegList("rf1").isLeft)
  }

  for (name, in) <- Seq("missing colon" -> "rf1500", "empty record id" -> ":500", "non-integer amount" -> "rf1:abc",
      "decimal amount rejected" -> "rf1:5.00")
  do
    test(s"legs_test.go/TestParseContribs_Malformed/$name") {
      assert(Resolver.parseLegList(in).isLeft, in)
    }

  test("legs_test.go/TestContribsRoundTrip") {
    Seq(Vector.empty[Leg], Vector(Leg("rf1", 500)), Vector(Leg("rf1", 500), Leg("rf2", 250), Leg("rf3", 1))).foreach { c =>
      val formatted = Resolver.formatLegList(c)
      assertEquals(Resolver.parseLegList(formatted), Right(c), s"via $formatted")
    }
  }

  test("legs_test.go/TestParseBreakdown_TrailingPipeIndifferent") {
    val withPipe    = Resolver.parseLegList("rf1:500|rf2:250|")
    val withoutPipe = Resolver.parseLegList("rf1:500|rf2:250")
    assert(withPipe.isRight)
    assertEquals(withPipe, withoutPipe)
  }
