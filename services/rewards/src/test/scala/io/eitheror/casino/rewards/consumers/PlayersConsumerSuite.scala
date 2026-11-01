package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rewards.clients.{BrandConfig, Called, Players}
import io.eitheror.casino.rewards.player.PlayerChange

import java.time.Instant

/** data-model.md's `PlayersConsumer` (research R9): what each family of `casino.players` changes, and the detection switch. */
class PlayersConsumerSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val reg = Instant.parse("2026-11-01T11:55:00Z")

  test("each family read is the player's change"):
    val cases = Vector[(CasinoPlayersFact, PlayerChange)](
      PlayerRegistered("p-1", reg, None, None, None, Some("GB"), Some("EUR"), None, at) -> PlayerChange.Registered(reg, Some("EUR")),
      PlayerDuplicateCheckStarted("p-1", reg, at) -> PlayerChange.CheckStarted(reg),
      PlayerDuplicateCheckEnded("p-1", "weak", at) -> PlayerChange.CheckEnded("weak"),
      PlayerNoDepositBlocked("p-1", at)            -> PlayerChange.Blocked,
      PlayerNoDepositBlockCleared("p-1", "op-1", at) -> PlayerChange.BlockCleared,
      PlayerFraudFlagged("p-1", "case-1", "multi_account", at) -> PlayerChange.Flag(true),
      PlayerFraudCleared("p-1", "case-1", at)      -> PlayerChange.Flag(false),
      VipTierChanged("p-1", None, "gold", "op-1", at) -> PlayerChange.Tier("gold")
    )
    cases.foreach((fact, change) => assertEquals(PlayersConsumer.change(fact), Some("p-1" -> change), fact.toString))

  test("only the check's facts read the switch again"):
    assert(PlayersConsumer.isCheck(PlayerChange.CheckStarted(reg)))
    assert(PlayersConsumer.isCheck(PlayerChange.CheckEnded("clear")))
    assert(!PlayersConsumer.isCheck(PlayerChange.Flag(true)))
    assert(!PlayersConsumer.isCheck(PlayerChange.Blocked))

  test("a family not read, and a fact that cannot be read, are ignored"):
    assertEquals(PlayersConsumer.change(PlayerLoggedIn("p-1", "s-1", at)), None)
    val kit = ConsumerTestKit.of(PlayersConsumer)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(Some(PlayerLoggedIn("p-1", "s-1", at))).effect, ConsumerEffect.Ignore)

  test("the switch: read when none is known and on a check fact; a failed read keeps the last value"):
    final class Answers(var next: Called[BrandConfig]) extends Players:
      var reads = 0
      def brandConfig(): Called[BrandConfig] = { reads += 1; next }
    Detection.forget()
    val players = Answers(Called.Done(BrandConfig(false)))
    assertEquals(Detection.current(players, refresh = false), Some(false))
    assertEquals(Detection.current(players, refresh = false), Some(false))
    assertEquals(players.reads, 1)
    players.next = Called.Unreachable("down")
    assertEquals(Detection.current(players, refresh = true), Some(false))
    players.next = Called.Done(BrandConfig(true))
    assertEquals(Detection.current(players, refresh = true), Some(true))
    assertEquals(players.reads, 3)
    Detection.forget()
    players.next = Called.Unreachable("down")
    assertEquals(Detection.current(players, refresh = false), None)
    Detection.forget()
