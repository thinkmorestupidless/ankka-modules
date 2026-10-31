package io.eitheror.casino.kyc.documents

import io.eitheror.casino.kyc.record.Category

import java.time.Instant

class ObjectNamesSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-30T12:00:00.123Z")

  test("a name is the player, the category and the time, with the content type's extension"):
    assertEquals(ObjectNames.of("p-1", Category.IdentityFront, at, "image/jpeg"), Some(s"subjects/p-1/kyc/identity_front-${at.toEpochMilli}.jpg"))
    assertEquals(ObjectNames.of("p-1", Category.ProofOfAddress, at, "image/png").map(_.takeRight(4)), Some(".png"))
    assertEquals(ObjectNames.of("p-1", Category.Other, at, "application/pdf").map(_.takeRight(4)), Some(".pdf"))

  test("only the three content types are accepted"):
    assertEquals(ObjectNames.Accepted.keySet, Set("image/jpeg", "image/png", "application/pdf"))
    for t <- Vector("image/gif", "text/html", "application/octet-stream", "") do
      assertEquals(ObjectNames.of("p-1", Category.Other, at, t), None, t)
      assert(!ObjectNames.accepted(t), t)

  test("nothing but the player id and the category is in a name"):
    for c <- Category.values do
      val name = ObjectNames.of("p-1", c, at, "image/jpeg").get
      assertEquals(name.split('/').toVector, Vector("subjects", "p-1", "kyc", s"${c.wire}-${at.toEpochMilli}.jpg"), c)

  test("every name is under the player's prefix, which the erasure deletes"):
    assert(ObjectNames.of("p-1", Category.Other, at, "image/png").get.startsWith(ObjectNames.prefix("p-1")))
    assertEquals(ObjectNames.prefix("p-1"), "subjects/p-1/")
