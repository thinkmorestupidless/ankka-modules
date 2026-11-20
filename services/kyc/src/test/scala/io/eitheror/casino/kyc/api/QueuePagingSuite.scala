package io.eitheror.casino.kyc.api

/** The queue's `page` and `pageSize` (spec 030 K2): none with neither, 1 and 50 by default with one, each in range. */
class QueuePagingSuite extends munit.FunSuite:
  private def paging(q: (String, String)*) = OperatorEndpoint.paging(q.toMap.get)

  test("with neither, the queue is not paged"):
    assertEquals(paging(), Right(None))
    assertEquals(paging("page" -> " "), Right(None))

  test("either one pages it, the other at its default"):
    assertEquals(paging("page" -> "3"), Right(Some((3, 50))))
    assertEquals(paging("pageSize" -> "200"), Right(Some((1, 200))))

  test("out of range or not a number names the parameter"):
    assertEquals(paging("page" -> "0"), Left("page"))
    assertEquals(paging("page" -> "two"), Left("page"))
    assertEquals(paging("pageSize" -> "201"), Left("pageSize"))
    assertEquals(paging("page" -> "1", "pageSize" -> "0"), Left("pageSize"))
