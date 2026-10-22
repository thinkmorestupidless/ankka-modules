package io.eitheror.casino.contract

import io.eitheror.casino.contract.Layout.{Grant, GrantTarget}

/**
 * The layout read from the module manifests (spec 021 T009) says what spec 001's fixed lists said,
 * for the core and the casino together, and adds only what spec 021 adds.
 */
class LayoutSuite extends munit.FunSuite:

  /** Spec 001 FR-002's twelve services, and the four spec 021 makes modules of their own or leaves to spec 016. */
  private val spec001Services = Vector(
    "wallet", "payouts", "players", "kyc", "responsible-gambling", "rewards", "game-provider",
    "attribution", "partner-api", "engagement", "reporting", "support-agent"
  )
  private val spec001Topics = Vector(
    "money.transactions", "payments.payments", "casino.players", "casino.sessions", "casino.rounds",
    "casino.withdrawals", "casino.rewards", "affiliates.attribution", "casino.push", "platform.audit"
  )
  private val spec001Grants = Vector(
    Grant("service:payments/merchant", GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits")),
    Grant("service:payments/merchant", GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/payments/{paymentId}/outcome")),
    Grant("service:payments/psp-gateway", GrantTarget.Route("payouts", "POST", "/v1/casino/brands/{brand}/withdrawals/{requestId}/outcome")),
    Grant("service:programme/affiliate-programme", GrantTarget.Topic("affiliates.attribution", "consume")),
    Grant("service:reasoner/audit", GrantTarget.Topic("platform.audit", "consume"))
  )

  private def of(names: String*) = Layout.Modules.filter(m => names.contains(m.name))

  test("the modules are core first, then the product modules of the tree") {
    assertEquals(Layout.Modules.map(_.name), Vector("core", "casino", "engagement", "reporting", "support"))
    assertEquals(Layout.Modules.map(_.kind).distinct, Vector("core", "product"))
  }

  test("core and casino run spec 001's services less those now a module of their own or spec 016's") {
    assertEquals(of("core", "casino").flatMap(_.services).toSet,
      spec001Services.toSet -- Set("partner-api", "engagement", "reporting", "support-agent"))
    assertEquals(Layout.Services.toSet, spec001Services.toSet - "partner-api")
    assertEquals(Layout.Services.distinct, Layout.Services, "a service is in one module only")
    assertEquals(Layout.moduleOf("game-provider"), Some("casino"))
  }

  test("core and casino declare spec 001's topics less engagement's push, plus platform.modules") {
    assertEquals(of("core", "casino").flatMap(_.topics).toSet, spec001Topics.toSet - "casino.push" + "platform.modules")
    assertEquals(Layout.BrandTopics.toSet, spec001Topics.toSet + "platform.modules")
  }

  test("the grants made are spec 001 FR-016's five, plus audit's consume of platform.modules") {
    assertEquals(Layout.BrandGrantsMade, spec001Grants :+ Grant("service:reasoner/audit", GrantTarget.Topic("platform.modules", "consume")))
  }

  test("the received grants stay shared payments' to make, per brand (E2)") {
    assertEquals(Layout.brandGrantsReceived("goldvibe").map(_.principal).toSet, Set("service:goldvibe/payouts", "service:goldvibe/players"))
  }

  test("the longest brand service still sets the slug's limit") {
    assertEquals(Layout.MaxSlugLength, 42)
  }
