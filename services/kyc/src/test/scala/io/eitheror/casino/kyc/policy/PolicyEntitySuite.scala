package io.eitheror.casino.kyc.policy

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

import java.time.Instant

/** data-model.md's `PolicyEntity` table, row by row (T012). */
class PolicyEntitySuite extends munit.FunSuite with LogCapturing:
  private val at    = Instant.parse("2026-10-30T12:00:00Z")
  private def fresh = EventSourcedTestKit.of(PolicyEntity, "goldvibe")

  test("with no event the policy is the Curaçao default, marked as one"):
    val kit = fresh
    assertEquals(kit.call(PolicyEntity.get).replyValue, Policy.Default)
    assert(Policy.Default.isDefault)

  test("ApplyProfile sets every field, and PolicyChanged carries before and after"):
    val kit    = fresh
    val result = kit.call(PolicyEntity.applyProfile)(ApplyProfile(Some("ukgc"), "op-1", at))
    assertEquals(result.replyValue, PolicyAnswer(Profiles.Ukgc))
    assertEquals(result.events, Seq(PolicyChanged(Policy.Default, Profiles.Ukgc, "op-1", at)))
    assertEquals(kit.currentState, Profiles.Ukgc)

  test("ApplyProfile refuses none and an unknown one, naming the known; nothing is recorded"):
    val kit = fresh
    assertEquals(kit.call(PolicyEntity.applyProfile)(ApplyProfile(None, "op-1", at)).replyValue.refusal, Some("profile_required"))
    val unknown = kit.call(PolicyEntity.applyProfile)(ApplyProfile(Some("GRA"), "op-1", at))
    assertEquals(unknown.replyValue, PolicyAnswer(Policy.Default, Some("unknown_profile"), known = Vector("CURACAO", "MGA", "UKGC")))
    assertEquals(unknown.events, Seq.empty)

  test("Change sets the profile CUSTOM and clears the default mark"):
    val kit    = fresh
    val result = kit.call(PolicyEntity.change)(ChangePolicy(Change(windowDays = Some(Some(7))), "op-1", at))
    assertEquals(result.replyValue.policy, Profiles.Curacao.copy(windowDays = Some(7), profile = Profile.Custom))
    assertEquals(result.events.map(_.before), Seq(Policy.Default))

  test("Change refuses each invalid field, naming it, and records nothing"):
    val kit = fresh
    def refusal(c: Change) =
      val r = kit.call(PolicyEntity.change)(ChangePolicy(c, "op-1", at))
      assertEquals(r.events, Seq.empty)
      (r.replyValue.refusal, r.replyValue.field)
    assertEquals(refusal(Change(depositGate = Some("ALWAYS"))), (Some("invalid_policy"), Some("depositGate")))
    assertEquals(refusal(Change(sofThreshold = Some(Some(-5L)))), (Some("invalid_policy"), Some("sofThreshold")))
    assertEquals(refusal(Change(depositThreshold = Some(Some(10L)))), (Some("currency_required"), None))
    assertEquals(refusal(Change(required = Some(Vector("identity_back")))), (Some("invalid_category"), Some("identity_back")))
    assertEquals(kit.currentState, Policy.Default)

  test("a change that changes nothing records nothing"):
    val kit = fresh
    kit.call(PolicyEntity.applyProfile)(ApplyProfile(Some("MGA"), "op-1", at)): Unit
    assertEquals(kit.call(PolicyEntity.applyProfile)(ApplyProfile(Some("MGA"), "op-2", at)).events, Seq.empty)

  test("a change clearing a threshold survives the command's serializer"):
    val s = PolicyEntity.given_Serializer_ChangePolicy
    val c = ChangePolicy(Change(sofThreshold = Some(None), depositThreshold = None), "op-1", at)
    assertEquals(s.fromBytes(s.toBytes(c)), c)
