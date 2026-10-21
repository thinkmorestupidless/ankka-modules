package io.eitheror.casino.merchant.routing

/** The walk of FR-012, case for case of the original's `RoutingTreeEvaluator`. */
class EvaluatorSuite extends munit.FunSuite:

  private val ctx = RoutingContext("EUR", 5000, "GB", "goldvibe:p1", 0)

  private def leaf(id: String, terminals: String*) = id -> Node(id, None, Some(terminals.toVector))
  private def fork(id: String, branches: Branch*) = id -> Node(id, Some(branches.toVector), None)
  private def when(next: String, cs: (String, String, String)*) = Branch(cs.toVector.map(Condition.apply), next)

  test("a catch-all root names its terminals") {
    assertEquals(Evaluator.evaluate(Tree("root", Map(leaf("root", "SIM"))), ctx), Outcome.Match(Vector("SIM")))
  }

  test("a tree with no nodes is no tree") {
    assertEquals(Evaluator.evaluate(Tree("root", Map.empty), ctx), Outcome.NoTree)
  }

  test("the first branch whose conditions all hold is taken") {
    val tree = Tree("root", Map(
      fork("root", when("big", ("AMOUNT", "GTE", "10000")), when("eur", ("CURRENCY", "EQ", "EUR"), ("COUNTRY", "EQ", "GB")), when("rest")),
      leaf("big", "T-big"), leaf("eur", "T-eur"), leaf("rest", "T-rest")))
    assertEquals(Evaluator.evaluate(tree, ctx), Outcome.Match(Vector("T-eur")))
    assertEquals(Evaluator.evaluate(tree, ctx.copy(amount = 10000)), Outcome.Match(Vector("T-big")))
    assertEquals(Evaluator.evaluate(tree, ctx.copy(country = "DE")), Outcome.Match(Vector("T-rest")))
  }

  test("a branch with no conditions always holds") {
    val tree = Tree("root", Map(fork("root", when("a")), leaf("a", "T")))
    assertEquals(Evaluator.evaluate(tree, ctx), Outcome.Match(Vector("T")))
  }

  test("no branch holding, a node with neither branches nor terminals, or a missing node is no match") {
    assertEquals(Evaluator.evaluate(Tree("root", Map(fork("root", when("a", ("AMOUNT", "LT", "10"))), leaf("a", "T"))), ctx), Outcome.NoMatch)
    assertEquals(Evaluator.evaluate(Tree("root", Map("root" -> Node("root", None, None))), ctx), Outcome.NoMatch)
    assertEquals(Evaluator.evaluate(Tree("root", Map("root" -> Node("root", None, Some(Vector.empty)))), ctx), Outcome.NoMatch)
    assertEquals(Evaluator.evaluate(Tree("root", Map(fork("root", when("gone")))), ctx), Outcome.NoMatch)
  }

  test("a tree with a cycle ends the walk at the first repeated node as no match") {
    val tree = Tree("a", Map(fork("a", when("b")), fork("b", when("a"))))
    assertEquals(Evaluator.evaluate(tree, ctx), Outcome.NoMatch)
  }

  test("amounts and deposit counts compare by each operator as whole numbers") {
    def h(t: String, op: String, v: String, c: RoutingContext = ctx) = Evaluator.holds(Condition(t, op, v), c)
    assert(h("AMOUNT", "LT", "5001") && !h("AMOUNT", "LT", "5000"))
    assert(h("AMOUNT", "LTE", "5000") && !h("AMOUNT", "LTE", "4999"))
    assert(h("AMOUNT", "GT", "4999") && !h("AMOUNT", "GT", "5000"))
    assert(h("AMOUNT", "GTE", "5000") && !h("AMOUNT", "GTE", "5001"))
    assert(h("AMOUNT", "EQ", "5000") && h("AMOUNT", "NEQ", "1"))
    assert(h("DEPOSIT_COUNT", "EQ", "0") && h("DEPOSIT_COUNT", "GT", "2", ctx.copy(depositCount = 3)))
    assert(!h("AMOUNT", "BETWEEN", "1"), "an unknown operator does not hold")
    assert(h("AMOUNT", "GT", "lots"), "a value that is not a number reads as zero, as the original's")
  }

  test("an amount beyond 32 bits compares exactly (research R11)") {
    val big = ctx.copy(amount = 3_000_000_000L)
    assert(Evaluator.holds(Condition("AMOUNT", "GT", "2147483647"), big))
  }

  test("currency, country and customer id compare ignoring case, IN splitting on commas") {
    def h(t: String, op: String, v: String) = Evaluator.holds(Condition(t, op, v), ctx)
    assert(h("CURRENCY", "EQ", "eur") && h("country", "eq", "gb"))
    assert(h("CURRENCY", "NEQ", "USD") && !h("CURRENCY", "NEQ", "EUR"))
    assert(h("COUNTRY", "IN", "DE, gb ,FR") && !h("COUNTRY", "IN", "DE,FR"))
    assert(h("CUSTOMER_ID", "EQ", "GOLDVIBE:P1"))
    assert(!h("CURRENCY", "LT", "EUR"), "an operator a text field does not take does not hold")
    assert(!h("BROWSER", "EQ", "x"), "an unknown condition does not hold")
  }

  test("a level is one of the original's three words") {
    assertEquals(Level.from("subBrand"), Some(Level.SubBrand))
    assertEquals(Level.from("sub_brand"), None)
    assertEquals(Level.values.map(_.api).toVector, Vector("holding", "company", "subBrand"))
  }
