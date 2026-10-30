package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.record.*

import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.duration.DurationInt

/**
 * The window's timer as the runtime runs it (R10, FR-025): set from the record's deadline, fired by the
 * sweeper with no help from the kit, `players` told to suspend once, retried while it fails and not again
 * after it answered.
 */
class WindowSuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    player = s"p-${UUID.randomUUID().toString.take(8)}"

  override def afterEach(context: AfterEach): Unit = kit.players.failing = false

  private def suspends(on: Boolean): Unit =
    val r = kit.json("PUT", kit.operatorPath("/policy"), Json.obj("kycWindowDays" -> 3, "autoSuspendOnWindowExpiry" -> on), kit.asOperator())
    assertEquals(r.status, 200, r.text)

  private def record = kit.components.forEventSourcedEntity(EntityId(player))
  private def state  = record.call(KycRecord.get).invoke()
  private def timer  = s"kyc-window-$player"

  /** A record whose deadline is `at`, as the migration opens one (migrated) or as a registration sets one. */
  private def deadline(at: Instant, migrated: Boolean = false): Unit =
    record.call(KycRecord.open).invoke(Open(KycState(playerId = player, deadline = Some(Deadline(at, migrated))), kit.now())): Unit

  test("a deadline lapses within a minute of its time, by the sweeper alone, and flags the player"):
    suspends(false)
    deadline(kit.now().plusSeconds(2))
    kit.eventually("the window flag", 60.seconds)(state.openFlag(FlagReason.Window))
    assert(state.deadline.exists(_.handled))
    assertEquals(kit.players.callsFor(player), Vector.empty)
    kit.eventually("the timer gone")(Option.when(!kit.timerExists(timer))(()))

  test("under a policy that suspends, players is told once, retried while it fails, and not again after it answered"):
    suspends(true)
    kit.players.failing = true
    deadline(kit.now().plusSeconds(1))
    kit.eventually("a second attempt", 60.seconds)(Option.when(kit.players.callsFor(player).size >= 2)(()))
    assert(!state.deadline.exists(_.suspensionRequested))
    kit.players.failing = false
    kit.eventually("the suspension recorded", 60.seconds)(Option.when(state.deadline.exists(_.suspensionRequested))(()))
    kit.eventually("the timer gone")(Option.when(!kit.timerExists(timer))(()))
    val calls = kit.players.callsFor(player).size
    Thread.sleep(5000)
    assertEquals(kit.players.callsFor(player).size, calls)
    val body = Json.parse(kit.players.callsFor(player).last.body).get
    assertEquals(body.get("reason").asText, "kyc_window_expired")
    assertEquals(state.flags.count(_.reason == FlagReason.Window), 1)

  test("a verified player's deadline changes nothing"):
    suspends(true)
    deadline(kit.now().plusSeconds(30))
    assertEquals(kit.json("POST", kit.operatorPath(s"/$player/override"), Json.obj("status" -> "verified", "reason" -> "checked"), kit.asOperator()).status, 200)
    kit.fireTimer(timer)
    kit.eventually("the timer gone")(Option.when(!kit.timerExists(timer))(()))
    assertEquals((state.flags, kit.players.callsFor(player)), (Vector.empty, Vector.empty))

  test("a closed player's timer is cancelled, and a fire for it changes nothing"):
    suspends(true)
    val at = kit.now().plusSeconds(30)
    deadline(at)
    kit.eventually("the timer")(Option.when(kit.timerExists(timer))(()))
    record.call(KycRecord.close).invoke(Close(kit.now())): Unit
    kit.eventually("the timer cancelled")(Option.when(!kit.timerExists(timer))(()))
    record.call(KycRecord.windowLapsed).invoke(WindowLapsed(at, true, kit.now())): Unit
    assertEquals((state.flags, kit.players.callsFor(player)), (Vector.empty, Vector.empty))

  test("a migrated deadline already past flags at once and never suspends"):
    suspends(true)
    deadline(kit.now().minus(Duration.ofDays(5)), migrated = true)
    kit.eventually("the window flag", 60.seconds)(state.openFlag(FlagReason.Window))
    kit.eventually("the timer gone")(Option.when(!kit.timerExists(timer))(()))
    Thread.sleep(1500)
    assertEquals(kit.players.callsFor(player), Vector.empty)
