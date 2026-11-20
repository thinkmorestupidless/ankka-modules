package io.eitheror.casino.attribution.api

/** An operator list's page (spec 030 L): the parameters read, and how many rows come before the page. */
class PagingSuite extends munit.FunSuite:

  test("neither parameter named is no paging; one named defaults the other to page 1 or 50 a page") {
    assertEquals(Paging.from(None, None), Right(None))
    assertEquals(Paging.from(Some("3"), None), Right(Some(Paging.Page(3, 50))))
    assertEquals(Paging.from(None, Some("200")), Right(Some(Paging.Page(1, 200))))
  }

  test("a page below 1, a size outside 1 to 200 or a word is refused, naming the parameter") {
    assertEquals(Paging.from(Some("0"), None), Left("page"))
    assertEquals(Paging.from(Some("x"), Some("10")), Left("page"))
    assertEquals(Paging.from(None, Some("201")), Left("pageSize"))
    assertEquals(Paging.from(None, Some("0")), Left("pageSize"))
  }

  test("the rows before a page, without overflow") {
    assertEquals(Paging.Page(1, 50).skip, 0L)
    assertEquals(Paging.Page(3, 20).skip, 40L)
    assertEquals(Paging.Page(Int.MaxValue, 200).skip, (Int.MaxValue.toLong - 1) * 200)
  }
