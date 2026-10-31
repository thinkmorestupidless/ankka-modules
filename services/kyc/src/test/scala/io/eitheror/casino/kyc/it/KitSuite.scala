package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.kyc.policy.{Policy, PolicyEntity}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** The plumbing: the components start against Postgres, and the store signs, reads and deletes against Garage (R7). */
class KitSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  test("the policy entity answers the Curaçao default through the component client"):
    assertEquals(kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(PolicyEntity.get).invoke(), Policy.Default)

  test("a signed PUT stores the object with its type; HEAD reads it; a signed GET returns it"):
    val name = "subjects/kit-1/kyc/other-1.png"
    val put  = kit.store.signPut(name, "image/png", Duration.ofHours(1)).toOption.get
    assertEquals(kit.putSigned(put, "png bytes".getBytes(UTF_8), "image/png") / 100, 2)
    assertEquals(kit.store.head(name), Right(Some(io.eitheror.casino.kyc.documents.Head(9L, "image/png"))))
    val (status, body) = kit.getSigned(kit.store.signGet(name, Duration.ofMinutes(15)).toOption.get)
    assertEquals((status, String(body, UTF_8)), (200, "png bytes"))
    assertEquals(kit.store.head("subjects/kit-1/kyc/none.png"), Right(None))

  test("deleting everything under a player's prefix leaves nothing listed, and only that player's"):
    for n <- Vector("subjects/kit-2/kyc/a.png", "subjects/kit-2/kyc/b.png", "subjects/kit-3/kyc/c.png") do
      kit.putSigned(kit.store.signPut(n, "image/png", Duration.ofHours(1)).toOption.get, Array[Byte](1, 2), "image/png"): Unit
    assert(kit.store.deleteAll("subjects/kit-2/").exists(_ >= 2))
    assertEquals(kit.store.head("subjects/kit-2/kyc/a.png"), Right(None))
    assertEquals(kit.store.deleteAll("subjects/kit-2/"), Right(0))
    assert(kit.store.head("subjects/kit-3/kyc/c.png").exists(_.isDefined))
