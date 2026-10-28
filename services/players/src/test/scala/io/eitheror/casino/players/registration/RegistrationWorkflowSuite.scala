package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.players.it.{Reply, SharedKit}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerState, Status}

import java.time.Duration as JDuration
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/**
 * `RegistrationWorkflow` on the integration kit (0.13 has no workflow testkit): its steps, its id, the
 * realm's refusals, the undo of a realm user whose player could not be written (FR-002), the
 * verification send left pending, and two registrations of one address joining.
 */
class RegistrationWorkflowSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def address = s"wf-${UUID.randomUUID().toString.take(8)}@example.test"
  private def flow(email: String): RegistrationFlow =
    kit.components.forWorkflow(RegistrationWorkflow.idOf(kit.slug, email)).call(RegistrationWorkflow.status).invoke()
  private def player(id: String): PlayerState =
    kit.components.forEventSourcedEntity(PlayerEntity.id(id)).call(PlayerEntity.get).invoke()
  private def pending(email: String): PendingRequest =
    kit.components.forKeyValueEntity(EntityId(RegistrationWorkflow.idOf(kit.slug, email).toString)).call(PendingRegistration.get).invoke()
  private def ended(email: String): RegistrationFlow =
    kit.eventually(s"the registration of $email ends")(Some(flow(email)).filter(_.step == RegistrationFlow.Ended))

  test("the steps run in order: the realm user with the player id, the player, the email; the request forgotten at the end") {
    val email = address
    val r     = kit.register("email" -> email)
    assertEquals(r.status, 201, r.text)
    val id = r.str("playerId")
    val f  = ended(email)
    assertEquals((f.playerId, f.outcome.flatMap(_.playerId), f.outcome.flatMap(_.status)), (id, Some(id), Some(Status.Pending)))
    assertEquals(kit.realm.userByEmail(email).map(_.playerId), Some(id))
    assertEquals(player(id).status, Status.Pending)
    assertEquals(kit.engagement.sendsTo(email).map(_.playerId), Vector(id))
    assertEquals(pending(email).request, None)
    assert(player(id).verificationEmail.lastSentAt.isDefined)
  }

  test("the id is the brand and the address lower-cased: A@x and a@x share one workflow") {
    assertEquals(RegistrationWorkflow.idOf("goldvibe", "Ada@Example.test "), RegistrationWorkflow.idOf("goldvibe", "ada@example.test"))
    assertNotEquals(RegistrationWorkflow.idOf("goldvibe", "ada@example.test"), RegistrationWorkflow.idOf("silvervibe", "ada@example.test"))
    val email = address
    assertEquals(kit.register("email" -> email).status, 201)
    ended(email)
    val again = kit.register("email" -> email.toUpperCase)
    assertEquals((again.status, again.str("error")), (409, "email_already_exists"))
  }

  test("the realm holding the address ends the attempt email_already_exists, and no player is written for it") {
    val email = address
    assertEquals(kit.register("email" -> email).status, 201)
    val first = ended(email).playerId
    val r     = kit.register("email" -> email)
    assertEquals((r.status, r.str("error")), (409, "email_already_exists"))
    val second = ended(email)
    assertNotEquals(second.playerId, first)
    assert(!player(second.playerId).exists)
    assertEquals(kit.realm.receivedOf("create").size, 2)
  }

  test("a password the realm refuses ends the attempt password_too_weak: no realm user, no player") {
    val email = address
    val r     = kit.register("email" -> email, "password" -> "short")
    assertEquals((r.status, r.str("error")), (422, "password_too_weak"))
    assertEquals(kit.realm.userByEmail(email), None)
    assert(!player(ended(email).playerId).exists)
  }

  test("a player that cannot be written: undo-realm removes the realm user and the answer is service_unavailable; a new attempt then succeeds") {
    val email = address
    RegistrationRuntime.write = (_, _) => throw IllegalStateException("the player write fails (suite)")
    val r = kit.register("email" -> email)
    assertEquals((r.status, r.str("error")), (503, "service_unavailable"))
    val f = ended(email)
    assertEquals(f.outcome.flatMap(_.refusal), Some("service_unavailable"))
    assert(kit.realm.receivedOf("delete").nonEmpty)
    assertEquals(kit.realm.userByEmail(email), None)
    assert(!player(f.playerId).exists)
    assertEquals(pending(email).request, None)

    RegistrationRuntime.reset()
    val again = kit.register("email" -> email)
    assertEquals(again.status, 201, again.text)
    assertEquals(kit.realm.userByEmail(email).map(_.playerId), Some(again.str("playerId")))
  }

  test("a realm answer that comes after the bound: the user made late is removed and the answer is service_unavailable") {
    val email = address
    kit.realm.delayMillis = 6000
    val r = kit.register("email" -> email)
    assertEquals((r.status, r.str("error")), (503, "service_unavailable"))
    kit.realm.delayMillis = 0
    kit.eventually("the late realm user removed", 40.seconds)(Option.when(kit.realm.receivedOf("create").nonEmpty &&
      kit.realm.userByEmail(email).isEmpty)(()))
    assertEquals(ended(email).outcome.flatMap(_.refusal), Some("service_unavailable"))
  }

  test("engagement silent: the player exists pending verification, the send is retried for a minute, then left pending") {
    val email = address
    kit.engagement.silent = true
    val r = kit.register("email" -> email)
    assertEquals(r.status, 201, r.text)
    val id = r.str("playerId")
    kit.eventually("the email recorded pending")(Option.when(player(id).verificationEmail.pending)(()))
    kit.eventually("a second attempt")(Option.when(kit.engagement.received.size >= 2)(()))
    assertEquals(flow(email).step, RegistrationFlow.Email)
    kit.advance(JDuration.ofSeconds(61))
    val f = kit.eventually("the attempt ends", 60.seconds)(Some(flow(email)).filter(_.step == RegistrationFlow.Ended))
    assert(f.sends >= 2, f.toString)
    assert(player(id).verificationEmail.pending)
    assertEquals(player(id).status, Status.Pending)
    assertEquals(pending(email).request, None)
    kit.engagement.silent = false
  }

  test("a second registration of one address while the first runs joins it: one realm user, one player, the same answer") {
    val email = address
    kit.realm.delayMillis = 1500
    val both = Future.sequence(Seq(Future(kit.register("email" -> email)), Future { Thread.sleep(300); kit.register("email" -> email) }))
    val Seq(a, b): Seq[Reply] = Await.result(both, 30.seconds): @unchecked
    assertEquals((a.status, b.status), (201, 201), s"${a.text} / ${b.text}")
    assertEquals(a.str("playerId"), b.str("playerId"))
    assertEquals(kit.realm.receivedOf("create").size, 1)
  }
