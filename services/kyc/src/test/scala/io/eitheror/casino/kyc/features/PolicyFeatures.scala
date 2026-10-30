package io.eitheror.casino.kyc.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit}
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.api.Render
import io.eitheror.casino.kyc.it.Reply
import io.eitheror.casino.kyc.policy.{Policy, PolicyEntity}

import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * features/kyc/policy.feature (US3, FR-026 to FR-029). The policy is read and written through the
 * operator's routes in the original's field names (contracts/wire.md). The brand the kit serves has had a
 * policy since its first scenario, so "a brand with no KYC policy" is a brand of its own, read from its
 * entity and rendered as the route renders it.
 */
class PolicyFeatures extends KycFeatures("policy.feature"):

  private var read: JsonNode   = null
  private var before: JsonNode = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    read = null; before = null

  private def policy(): JsonNode =
    val r = kit.request("GET", kit.operatorPath("/policy"), None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    r.json

  private def applyThroughRoute(name: String): Reply =
    last = kit.json("POST", kit.operatorPath("/policy/profile"), json("profile" -> name), kit.asOperator())
    last

  private def isNull(p: JsonNode, field: String) = Option(p.get(field)).exists(_.isNull)

  private def fields(p: JsonNode): Map[String, String] = p.properties.asScala.map(e => e.getKey -> e.getValue.toString).toMap

  // ── reading ───────────────────────────────────────────────────────────────────────────────────

  private var fresh = ""

  Given("a brand with no KYC policy") { () => fresh = s"brand-${UUID.randomUUID().toString.take(8)}" }

  When("its KYC policy is read") { () =>
    val p = kit.components.forEventSourcedEntity(EntityId(fresh)).call(PolicyEntity.get).invoke()
    assertEquals(p, Policy.Default)
    read = Render.policy(p)
  }

  Then("it is the {string} default: no deposit gate, no play gate, no KYC currency, no deposit threshold, a {string} day verification window, no source of funds threshold, identity required, and suspend on expiry") {
    (profile: String, days: String) =>
      assertEquals(read.text("licenceProfile").map(_.toLowerCase), Some(profile))
      assertEquals((read.text("depositGate"), read.text("playGate")), (Some("NONE"), Some("NONE")))
      Seq("kycCurrency", "depositThresholdMinorUnits", "sofThresholdMinorUnits").foreach(f => assert(isNull(read, f), f))
      assertEquals(read.long("kycWindowDays"), Some(days.toLong))
      assertEquals(read.strings("requiredDocTypes"), Vector("identity"))
      assertEquals((read.bool("autoSuspendOnWindowExpiry"), read.bool("isDefault")), (Some(true), Some(true)))
      // The route renders the same fields for the kit's brand on the same profile.
      assertEquals(fields(policy()) - "isDefault", fields(read) - "isDefault")
  }

  // ── a profile ─────────────────────────────────────────────────────────────────────────────────

  Given("a brand") { () => () }

  When("an operator applies the licence profile {string}") { (name: String) => applyThroughRoute(name) }

  Then("the KYC policy is: deposit gate verified, play gate verified, the KYC currency {string}, no deposit threshold, a {string} day verification window, a source of funds threshold of {string} minor units, identity and proof of address required, and suspend on expiry") {
    (currency: String, days: String, sof: String) =>
      assertEquals(last.status, 200, last.text)
      val p = policy()
      assertEquals(fields(p), fields(last.json))
      assertEquals((p.text("depositGate"), p.text("playGate"), p.text("kycCurrency")), (Some("KYC_APPROVED"), Some("KYC_APPROVED"), Some(currency)))
      assert(isNull(p, "depositThresholdMinorUnits"))
      assertEquals((p.long("kycWindowDays"), p.long("sofThresholdMinorUnits")), (Some(days.toLong), Some(sof.toLong)))
      assertEquals(p.strings("requiredDocTypes"), Vector("identity", "proof_of_address"))
      assertEquals((p.bool("autoSuspendOnWindowExpiry"), p.text("licenceProfile")), (Some(true), Some("UKGC")))
  }

  Then("the KYC policy is: no deposit gate, no play gate, the KYC currency {string}, a deposit threshold of {string}, no verification window, a source of funds threshold of {string}, identity and proof of address required, and no suspension on expiry") {
    (currency: String, deposit: String, sof: String) =>
      assertEquals(last.status, 200, last.text)
      val p = policy()
      assertEquals((p.text("depositGate"), p.text("playGate"), p.text("kycCurrency")), (Some("NONE"), Some("NONE"), Some(currency)))
      assertEquals((p.long("depositThresholdMinorUnits"), p.long("sofThresholdMinorUnits")), (Some(deposit.toLong), Some(sof.toLong)))
      assert(isNull(p, "kycWindowDays"))
      assertEquals(p.strings("requiredDocTypes"), Vector("identity", "proof_of_address"))
      assertEquals((p.bool("autoSuspendOnWindowExpiry"), p.text("licenceProfile")), (Some(false), Some("MGA")))
  }

  Given("a brand on the licence profile {string}") { (name: String) =>
    assertEquals(applyThroughRoute(name).status, 200, last.text)
    before = policy()
  }

  When("an operator sets the verification window to {string} days") { (days: String) =>
    last = kit.json("PUT", kit.operatorPath("/policy"), json("kycWindowDays" -> days.toInt), kit.asOperator())
    assertEquals(last.status, 200, last.text)
  }

  Then("only that field changes") { () =>
    val after   = policy()
    val changed = (fields(before).keySet ++ fields(after).keySet).filter(k => fields(before).get(k) != fields(after).get(k))
    assertEquals(changed, Set("kycWindowDays", "licenceProfile"))
    assertEquals(after.long("kycWindowDays"), Some(60L))
  }

  Then("the licence profile reads {string}") { (name: String) =>
    assertEquals(policy().text("licenceProfile").map(_.toLowerCase), Some(name))
  }

  Then("it is refused naming the known licence profiles") { () =>
    assertEquals((last.status, last.str("error")), (400, "unknown_profile"))
    assertEquals(last.json.strings("knownProfiles"), Vector("CURACAO", "MGA", "UKGC"))
  }

  // ── a change, audited and in force ────────────────────────────────────────────────────────────

  private def brandAudits: Vector[AuditRecord] =
    kit.facts(PlatformAudit).collect { case r: AuditRecord if r.subjectKind == "brand" && r.subjectId == kit.slug => r }

  private var seen = 0

  Given("a KYC policy change") { () => seen = brandAudits.size }

  When("it is saved") { () =>
    last = kit.json("PUT", kit.operatorPath("/policy"), json("depositGate" -> "KYC_APPROVED"), kit.asOperator("op-policy"))
    assertEquals(last.status, 200, last.text)
  }

  Then("an audit record names the operator, the fields and the old and new values") { () =>
    val r = eventually("the policy's audit record")(brandAudits.drop(seen).find(r => r.action == "kyc.policy_changed" && r.actorId == "op-policy"))
    assert(r.changes.exists(c => c.field == "depositGate" && c.before.contains("NONE") && c.after.contains("KYC_APPROVED")), r.changes.toString)
  }

  Then("the next gate decision for any player uses it") { () =>
    val r = kit.json("POST", kit.servicePath("/deposit-check"), json("playerId" -> newPlayer(), "amount" -> 1000L, "currency" -> "EUR"),
      kit.asService("players"))
    assertEquals((r.status, r.json.bool("allowed")), (200, Some(false)), r.text)
  }

  // ── the public requirements ───────────────────────────────────────────────────────────────────

  Given("a brand's KYC policy") { () => applyProfile("MGA") }

  When("an unauthenticated client asks the brand's requirements") { () =>
    last = kit.request("GET", kit.publicPath(""), None, kit.asPublic)
  }

  Then("it is told the required categories and nothing else") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.json.properties.asScala.map(_.getKey).toSet, Set("requiredDocTypes"))
    assertEquals(last.json.strings("requiredDocTypes"), Vector("identity", "proof_of_address"))
  }
