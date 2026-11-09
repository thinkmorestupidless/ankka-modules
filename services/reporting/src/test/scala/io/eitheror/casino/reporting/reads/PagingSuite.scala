package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.DeclaredQuery

/** Review, should fix 5: a read pages to its last row, or refuses; it never answers part of what it asked for. */
class PagingSuite extends munit.FunSuite:
  private val rows = Vector.tabulate(25)(i => f"g1/p-$i%03d")

  /** A keyed page: at most `size` rows after `after`, in key order. */
  private def page(size: Int, asked: collection.mutable.Buffer[String])(after: String): Vector[String] =
    asked += after
    rows.filter(_ > after).take(size)

  test("every row, a page at a time after the last row's key, until a page is short") {
    val asked = collection.mutable.Buffer.empty[String]
    assertEquals(Paging.all(page(10, asked), identity[String], 10), rows)
    assertEquals(asked.toVector, Vector(Paging.First, "g1/p-009", "g1/p-019"))
  }

  test("a last page that is exactly full is followed by an empty one, not taken for the end") {
    val asked = collection.mutable.Buffer.empty[String]
    assertEquals(Paging.all(page(5, asked), identity[String], 5), rows)
    assertEquals(asked.size, 6)
  }

  test("a bounded read of more rows than its bound is refused, naming the view and the query") {
    val q = DeclaredQuery(ComponentId("rounds"), "by-day", "SELECT payload FROM t")
    assertEquals(Paging.checked(q, 25)(rows), rows)
    val refused = intercept[TooManyRows](Paging.checked(q, 24)(rows))
    assertEquals((refused.limit, refused.getMessage.contains("rounds/by-day")), (24, true))
  }
