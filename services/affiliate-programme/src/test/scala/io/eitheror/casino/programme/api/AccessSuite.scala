package io.eitheror.casino.programme.api

class AccessSuite extends munit.FunSuite:
  private val parents = Map("aff-3" -> "aff-2", "aff-2" -> "aff-1")

  test("a target is reached when it is the caller or below it") {
    assert(Access.reaches("aff-1", "aff-1", parents.get))
    assert(Access.reaches("aff-1", "aff-3", parents.get))
    assert(!Access.reaches("aff-3", "aff-1", parents.get))
    assert(!Access.reaches("aff-9", "aff-3", parents.get))
  }

  test("a cycle of parents ends the walk") {
    assert(!Access.reaches("aff-9", "a", Map("a" -> "b", "b" -> "a").get))
  }

  test("a manager's brand is the segment after /v1/affiliates/") {
    assertEquals(Access.brandOfPath("/v1/affiliates/goldvibe/accounts/aff-1"), Some("goldvibe"))
    assertEquals(Access.brandOfPath("/v1/my/aff-1"), None)
  }
