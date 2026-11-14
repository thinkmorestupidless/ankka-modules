package io.eitheror.casino.support.config

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant

/** data-model.md's `AgentConfiguration` and `ConfigVersion` (T020). */
class ConfigurationSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-09T10:00:00Z")

  private def note(v: Int) = ConfigNote(v, "op-1", Vector("enabled"), Map("enabled" -> "true"), at)

  test("the configuration moves only to the next version"):
    val kit = KeyValueEntityTestKit.of(AgentConfiguration, "goldvibe")
    val one = kit.call(AgentConfiguration.move)(Move(1, ConfigBody(enabled = true), note(1))).replyValue
    assertEquals((one.stale, one.state.version, one.state.current.enabled), (false, 1, true))
    val skipped = kit.call(AgentConfiguration.move)(Move(3, ConfigBody(), note(3))).replyValue
    assertEquals((skipped.stale, kit.currentState.version), (true, 1))
    val again = kit.call(AgentConfiguration.move)(Move(1, ConfigBody(), note(1))).replyValue
    assertEquals((again.stale, kit.currentState.current.enabled), (true, true))

  test("the last 16 notes are kept"):
    val kit = KeyValueEntityTestKit.of(AgentConfiguration, "goldvibe")
    (1 to 20).foreach(v => kit.call(AgentConfiguration.move)(Move(v, ConfigBody(turnsPerMinute = v), note(v))): Unit)
    assertEquals(kit.currentState.recentChanges.map(_.version), (5 to 20).toVector)
    assertEquals(kit.currentState.version, 20)

  test("a version's body is written once and never changed"):
    val kit = KeyValueEntityTestKit.of(ConfigVersion, "goldvibe/1")
    val at = java.time.Instant.parse("2026-11-11T10:00:00Z")
    assertEquals(kit.call(ConfigVersion.write)(ConfigChange(ConfigBody(model = "m-1"), "op-1", at)).replyValue,
      ConfigChange(ConfigBody(model = "m-1"), "op-1", at))
    assertEquals(kit.call(ConfigVersion.write)(ConfigChange(ConfigBody(model = "m-2"), "op-2", at)).replyValue,
      ConfigChange(ConfigBody(model = "m-1"), "op-1", at))
    assertEquals(kit.currentState.body.map(_.model), Some("m-1"))
