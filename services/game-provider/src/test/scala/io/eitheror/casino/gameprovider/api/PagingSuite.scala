package io.eitheror.casino.gameprovider.api

/** An operator list's page (spec 030 L): the parameters read, and the page cut. */
class PagingSuite extends munit.FunSuite:

  test("neither parameter named is no paging; one named defaults the other to page 1 or 50 a page") {
    assertEquals(Paging.from(None, None), Right(None))
    assertEquals(Paging.from(Some(" "), None), Right(None))
    assertEquals(Paging.from(Some("3"), None), Right(Some(Paging.Page(3, 50))))
    assertEquals(Paging.from(None, Some("200")), Right(Some(Paging.Page(1, 200))))
  }

  test("a page below 1, a size outside 1 to 200 or a word is refused, naming the parameter") {
    assertEquals(Paging.from(Some("0"), None), Left("page"))
    assertEquals(Paging.from(Some("x"), None), Left("page"))
    assertEquals(Paging.from(None, Some("201")), Left("pageSize"))
    assertEquals(Paging.from(None, Some("0")), Left("pageSize"))
  }

  test("a page is its slice of the list; past the last page it is empty") {
    val all = (1 to 5).toVector
    assertEquals(Paging.Page(1, 2).of(all), Vector(1, 2))
    assertEquals(Paging.Page(3, 2).of(all), Vector(5))
    assertEquals(Paging.Page(4, 2).of(all), Vector.empty)
    assertEquals(Paging.Page(Int.MaxValue, 200).of(all), Vector.empty)
  }
