package io.eitheror.casino.contract.access

import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import io.eitheror.casino.contract.Layout.GrantTarget
import Requests.*

class RoutesSuite extends munit.FunSuite:

  private val self = RotatingTls.Identity("goldvibe", "wallet")

  private def outcome(acl: com.thinkmorestupidless.ankka.http.Acl, request: Request) = decide(acl, request, self)

  test("a player of this brand is served; a player of another brand is refused naming this brand") {
    val acl = Routes.player("goldvibe", verified)
    assertEquals(outcome(acl, Request(token = Some(playerOf("goldvibe")))), Outcome.Served)
    outcome(acl, Request(token = Some(playerOf("spinvibe")))) match
      case Outcome.Refused(403, reason) => assert(reason.startsWith("wrong_brand") && reason.contains("goldvibe"), reason)
      case other                        => fail(other.toString)
  }

  test("a player's id is the token's eitheror_twin_id, its last segment; never the realm's sub") {
    def principal(claims: (String, String)*) =
      com.thinkmorestupidless.ankka.http.Principal("realm-user-uuid", claims = claims.toMap, issuer = Some(Routes.PlayerIssuer))
    assertEquals(Routes.playerId(principal(Routes.PlayerIdClaim -> "p-1")), Some("p-1"))
    assertEquals(Routes.playerId(principal(Routes.PlayerIdClaim -> "casino/player/p-1")), Some("p-1"))
    assertEquals(Routes.playerId(principal()), None)
    assertEquals(Routes.playerId(principal(Routes.PlayerIdClaim -> "")), None)
  }

  test("a player route without a token asks for a sign-in") {
    assert(outcome(Routes.player("goldvibe", verified), Request()).isInstanceOf[Outcome.Refused])
    assertEquals(outcome(Routes.player("goldvibe", verified), Request()).asInstanceOf[Outcome.Refused].status, 401)
  }

  test("an operator is served where a realm role names the brand, and refused elsewhere however they signed in") {
    val token = operatorWith("brand_goldvibe", "operator")
    assertEquals(outcome(Routes.operator("goldvibe", verified), Request(token = Some(token))), Outcome.Served)
    assertEquals(outcome(Routes.operator("spinvibe", verified), Request(token = Some(token))).asInstanceOf[Outcome.Refused].status, 403)
    // signed in at a player realm, even one whose token carries a role named for the brand
    assertEquals(outcome(Routes.operator("goldvibe", verified), Request(token = Some(playerOf("goldvibe")))).asInstanceOf[Outcome.Refused].status, 403)
    assertEquals(outcome(Routes.operator("goldvibe", verified), Request(token = Some(operatorWith("platform_admin")))), Outcome.Served)
  }

  test("spec 023: an operator holding the markets role is served; a brand operator without it is refused naming it") {
    val acl = Routes.operatorHolding("goldvibe", "markets", "platform_admin")(verified)
    assertEquals(outcome(acl, Request(token = Some(operatorWith("brand_goldvibe", "markets")))), Outcome.Served)
    assertEquals(outcome(acl, Request(token = Some(operatorWith("platform_admin")))), Outcome.Served)
    val refused = outcome(acl, Request(token = Some(operatorWith("brand_goldvibe", "operator")))).asInstanceOf[Outcome.Refused]
    assertEquals(refused.status, 403)
    assert(refused.toString.contains("markets permission"), refused.toString)
    // the role without the brand is still the operator class's refusal
    assertEquals(outcome(acl, Request(token = Some(operatorWith("brand_spinvibe", "markets")))).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("a payments operator is served with a role of the table and a level from the scope claims") {
    val acl = Routes.paymentsOperator(verified)
    val company = paymentsOperator(Seq("resource_admin"), "eitheror_scope_level" -> "company", "eitheror_scope_id" -> "c-1")
    assertEquals(outcome(acl, Request(token = Some(company))), Outcome.Served)
    val platform = paymentsOperator(Seq("platform_admin"), "eitheror_scope_level" -> "platform")
    assertEquals(outcome(acl, Request(token = Some(platform))), Outcome.Served)
  }

  test("a payments operator token naming no level, an unknown level, or no id below the platform is refused") {
    val acl = Routes.paymentsOperator(verified)
    for token <- Vector(
        paymentsOperator(Seq("resource_admin")),
        paymentsOperator(Seq("resource_admin"), "eitheror_scope_level" -> "affiliate", "eitheror_scope_id" -> "a-1"),
        paymentsOperator(Seq("resource_admin"), "eitheror_scope_level" -> "company")
      )
    do
      outcome(acl, Request(token = Some(token))) match
        case Outcome.Refused(403, reason) => assert(reason.startsWith("scope_violation"), reason)
        case other                        => fail(s"$token: $other")
  }

  test("a payments operator route refuses a staff token with no payments role, a player token and no token") {
    val acl = Routes.paymentsOperator(verified)
    val noRole = paymentsOperator(Seq("brand_goldvibe"), "eitheror_scope_level" -> "platform")
    outcome(acl, Request(token = Some(noRole))) match
      case Outcome.Refused(403, reason) => assert(reason.startsWith("not_payments_operator"), reason)
      case other                        => fail(other.toString)
    assertEquals(outcome(acl, Request(token = Some(playerOf("goldvibe")))).asInstanceOf[Outcome.Refused].status, 403)
    assertEquals(outcome(acl, Request()).asInstanceOf[Outcome.Refused].status, 401)
  }

  test("the project's services, and through the ingress a payments operator, read a page both read") {
    val acl = Routes.projectOrPaymentsOperator("payments", verified)
    assertEquals(outcome(acl, Request(caller = Caller.Service("payments", "ingest"))), Outcome.Served)
    assert(outcome(acl, Request(caller = Caller.Service("goldvibe", "payouts"))).isInstanceOf[Outcome.Refused], "another project")
    val platform = paymentsOperator(Seq("platform_admin"), "eitheror_scope_level" -> "platform")
    assertEquals(outcome(acl, Request(token = Some(platform))), Outcome.Served)
    assertEquals(outcome(acl, Request()).asInstanceOf[Outcome.Refused].status, 401)
    assertEquals(outcome(acl, Request(token = Some(playerOf("goldvibe")))).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("customer_id is never read as an operator's level (decision 0050)") {
    val acl = Routes.paymentsOperator(verified)
    val token = paymentsOperator(Seq("resource_admin"), "customer_id" -> "eitheror")
    assert(outcome(acl, Request(token = Some(token))).isInstanceOf[Outcome.Refused])
  }

  test("an affiliate reaches the programme with the affiliate role only") {
    assertEquals(outcome(Routes.affiliate(verified), Request(token = Some(operatorWith("affiliate")))), Outcome.Served)
    assert(outcome(Routes.affiliate(verified), Request(token = Some(operatorWith("brand_goldvibe")))).isInstanceOf[Outcome.Refused])
  }

  test("spec 016: an operator of the brand holding api_keys, or a platform administrator, manages its access keys") {
    assertEquals(outcome(Routes.accessKeys("goldvibe", verified), Request(token = Some(operatorWith("brand_goldvibe", "api_keys")))), Outcome.Served)
    assertEquals(outcome(Routes.accessKeys("goldvibe", verified), Request(token = Some(operatorWith("platform_admin")))), Outcome.Served)
    outcome(Routes.accessKeys("goldvibe", verified), Request(token = Some(operatorWith("brand_goldvibe")))) match
      case Outcome.Refused(_, body) => assert(body.contains("forbidden"), body)
      case other                    => fail(s"admitted without api_keys: $other")
    outcome(Routes.accessKeys("goldvibe", verified), Request(token = Some(operatorWith("brand_spinvibe", "api_keys")))) match
      case Outcome.Refused(_, body) => assert(body.contains("brand_not_in_roles"), body)
      case other                    => fail(s"admitted at another brand: $other")
  }

  test("a service of the project is admitted by its identity, with no token; one of another project is not") {
    assertEquals(outcome(Routes.service(), Request(caller = Caller.Service("goldvibe", "game-provider"))), Outcome.Served)
    assert(outcome(Routes.service(), Request(caller = Caller.Service("spinvibe", "game-provider"))).isInstanceOf[Outcome.Refused])
    assert(outcome(Routes.service(), Request(caller = Caller.Gateway)).isInstanceOf[Outcome.Refused], "the internet is not a service")
  }

  test("the shared payments is admitted to the deposit route the brand grants it, and to nothing else") {
    val deposit = Routes.granted("wallet").find(_._1.path.endsWith("/deposits")).get._2
    val merchant = Request(caller = Caller.Service("payments", "merchant"))
    assertEquals(outcome(deposit, merchant), Outcome.Served)
    assert(outcome(Routes.service(), merchant).isInstanceOf[Outcome.Refused], "an ungranted route refuses it")
    assert(outcome(deposit, Request(caller = Caller.Service("payments", "psp-gateway"))).isInstanceOf[Outcome.Refused])
  }

  test("spec 022: the named service class admits the project's services and a route's grantees by name, and nobody else") {
    val moves: GrantTarget.Route = GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/accounts/{kind}/{name}/moves")
    val grants = Vector(io.eitheror.casino.contract.Layout.Grant("service:pool-north/clearing", moves))
    val acl    = Routes.serviceNamed("goldvibe", Some(moves), grants)
    def principal(caller: Caller): Option[String] = acl match
      case Acl.Authenticate(check) =>
        check(Request(caller = caller)) match
          case AuthDecision.Allow(p) => Some(p.subject)
          case _                     => None
      case other => fail(s"not an authenticating ACL: $other")
    assertEquals(principal(Caller.Service("goldvibe", "exchange")), Some("service:goldvibe/exchange"))
    assertEquals(principal(Caller.Service("pool-north", "clearing")), Some("service:pool-north/clearing"))
    assertEquals(principal(Caller.Service("pool-north", "markets")), None, "another service of the grantee's project")
    assertEquals(principal(Caller.Service("spinvibe", "exchange")), None, "another brand's service")
    assertEquals(principal(Caller.Gateway), None, "the internet is not a service")
    val ungranted = Routes.serviceNamed("goldvibe", None, grants)
    assert(outcome(ungranted, Request(caller = Caller.Service("pool-north", "clearing"))).isInstanceOf[Outcome.Refused],
      "a grantee of one route is not admitted to a route it was not granted")
  }

  test("the routes a brand grants are exactly the grants contract's") {
    assertEquals(Routes.granted("wallet").size, 2)
    assertEquals(Routes.granted("payouts").map(_._1.path), Vector("/v1/casino/brands/{brand}/withdrawals/{requestId}/outcome"))
    assertEquals(Routes.granted("players"), Vector.empty)
  }

  test("a brand holds three grants on psp-gateway, every one for its own payouts (spec 005 research R12)") {
    val gateway = io.eitheror.casino.contract.Layout.brandGrantsReceived("goldvibe").collect {
      case io.eitheror.casino.contract.Layout.Grant(principal, GrantTarget.Route("psp-gateway", method, path)) => (principal, s"$method $path")
    }
    assertEquals(gateway.map(_._2), Vector("POST /v1/payments/withdrawals", "GET /v1/payments/withdrawals/{requestId}",
      "GET /v1/payments/withdrawal-terminals"))
    assertEquals(gateway.map(_._1).toSet, Set("service:goldvibe/payouts"))
  }

  test("a public route is served to anyone") {
    assertEquals(outcome(Routes.public, Request()), Outcome.Served)
  }

  test("a brand refuses to answer about another brand, naming the brand it serves (FR-005)") {
    assertEquals(Routes.brandSegment("goldvibe", "goldvibe"), Right("goldvibe"))
    Routes.brandSegment("goldvibe", "spinvibe") match
      case Left(problem) =>
        assertEquals(problem.status, 404)
        assert(problem.message.contains("goldvibe"), problem.message)
      case Right(_) => fail("served another brand")
  }

  test("the routes every brand service serves players admit players of this brand and nothing else (spec 008)") {
    assertEquals(outcome(Routes.playersOnly, Request(caller = Caller.Service("goldvibe", "players"))), Outcome.Served)
    assert(outcome(Routes.playersOnly, Request(caller = Caller.Service("spinvibe", "players"))).isInstanceOf[Outcome.Refused], "another brand's players")
    assert(outcome(Routes.playersOnly, Request(caller = Caller.Service("goldvibe", "wallet"))).isInstanceOf[Outcome.Refused], "another service")
    assert(outcome(Routes.playersOnly, Request(token = Some(playerOf("goldvibe")))).isInstanceOf[Outcome.Refused], "a player")
    assert(outcome(Routes.playersOnly, Request(token = Some(operatorWith("brand_goldvibe")))).isInstanceOf[Outcome.Refused], "an operator")
  }

  test("a service route served to named callers admits each of them and no other service of the project") {
    val acl = Routes.serviceOf("kyc", "responsible-gambling")
    assertEquals(outcome(acl, Request(caller = Caller.Service("goldvibe", "kyc"))), Outcome.Served)
    assertEquals(outcome(acl, Request(caller = Caller.Service("goldvibe", "responsible-gambling"))), Outcome.Served)
    assert(outcome(acl, Request(caller = Caller.Service("goldvibe", "game-provider"))).isInstanceOf[Outcome.Refused])
    assert(outcome(acl, Request(caller = Caller.Gateway)).isInstanceOf[Outcome.Refused])
  }

  test("a signed-in recipe refuses an ACL that cannot authenticate") {
    intercept[IllegalArgumentException](Routes.player("goldvibe", com.thinkmorestupidless.ankka.http.Acl.AllowAll))
  }

  test("spec 013: an affiliate manager holds affiliate_manager, brand_admin or platform_admin at the eitheror realm") {
    val acl = Routes.affiliateManager(verified)
    Seq("affiliate_manager", "brand_admin", "platform_admin").foreach { role =>
      assertEquals(outcome(acl, Request(token = Some(operatorWith(role, "brand_goldvibe")))), Outcome.Served, role)
    }
    val refused = outcome(acl, Request(token = Some(operatorWith("brand_goldvibe", "operator")))).asInstanceOf[Outcome.Refused]
    assertEquals(refused.status, 403)
    assert(refused.toString.contains("not_affiliate_manager"), refused.toString)
    assertEquals(outcome(acl, Request(token = Some(playerOf("goldvibe")))).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("spec 018: audit compliance is audit_compliance, or a brand's audit_compliance_<slug>, at the eitheror realm") {
    val acl = Routes.auditCompliance(verified)
    assertEquals(outcome(acl, Request(token = Some(operatorWith("audit_compliance")))), Outcome.Served)
    assertEquals(outcome(acl, Request(token = Some(operatorWith("audit_compliance_goldvibe")))), Outcome.Served)
    Seq(operatorWith("brand_compliance", "brand_goldvibe"), operatorWith("brand_goldvibe", "operator"), operatorWith("platform_admin")).foreach { token =>
      val refused = outcome(acl, Request(token = Some(token))).asInstanceOf[Outcome.Refused]
      assertEquals(refused.status, 403, token)
      assert(refused.toString.contains("not_audit_compliance"), refused.toString)
    }
    assertEquals(outcome(acl, Request(token = Some(playerOf("goldvibe")))).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("spec 018: auditScope is every brand for audit_compliance, else exactly the brands of its audit_compliance_<slug> roles") {
    def principal(roles: String*) = com.thinkmorestupidless.ankka.http.Principal("c", roles = roles.toSet, issuer = Some(Routes.StaffIssuer))
    assertEquals(Routes.auditScope(principal("audit_compliance")), Routes.BrandSet.Every)
    // An operator of silvervibe given goldvibe's compliance reads goldvibe alone.
    assertEquals(Routes.auditScope(principal("audit_compliance_goldvibe", "brand_compliance", "brand_silvervibe", "brand_admin")),
      Routes.BrandSet.Only(Set("goldvibe")))
    assert(Routes.auditScope(principal("brand_compliance", "brand_goldvibe")).isEmpty, "the brand services' compliance reads nothing here")
    assert(Routes.auditScope(principal("audit_compliance_")).isEmpty)
    // The realm's static compliance_officer is not read as a brand's role.
    assert(Routes.auditScope(principal("compliance_officer")).isEmpty)
  }

  test("spec 018: re-identification needs the brand's compliance role and reidentify_<slug>; the installation's role never suffices") {
    def principal(roles: String*) = com.thinkmorestupidless.ankka.http.Principal("c", roles = roles.toSet, issuer = Some(Routes.StaffIssuer))
    assert(Routes.reidentifies(principal("audit_compliance_goldvibe", "reidentify_goldvibe"), "goldvibe"))
    assert(!Routes.reidentifies(principal("audit_compliance_goldvibe", "reidentify_goldvibe"), "silvervibe"))
    assert(!Routes.reidentifies(principal("audit_compliance_silvervibe", "reidentify_goldvibe"), "goldvibe"))
    assert(!Routes.reidentifies(principal("brand_compliance", "brand_goldvibe", "reidentify_goldvibe"), "goldvibe"))
    assert(!Routes.reidentifies(principal("audit_compliance", "reidentify_goldvibe"), "goldvibe"))
    assert(!Routes.reidentifies(principal("audit_compliance", "platform_admin"), "goldvibe"))
  }

  test("spec 018: an installation owner is platform_admin; any staff member reaches a route that records its refusals") {
    assertEquals(outcome(Routes.installationOwner(verified), Request(token = Some(operatorWith("platform_admin")))), Outcome.Served)
    val refused = outcome(Routes.installationOwner(verified), Request(token = Some(operatorWith("audit_compliance")))).asInstanceOf[Outcome.Refused]
    assert(refused.toString.contains("not_installation_owner"), refused.toString)
    assertEquals(outcome(Routes.staffMember(verified), Request(token = Some(operatorWith("operator")))), Outcome.Served)
    assertEquals(outcome(Routes.staffMember(verified), Request(token = Some(playerOf("goldvibe")))).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("spec 013: brandsOf is the brand_<slug> roles held, and every brand for platform_admin") {
    def principal(roles: String*) = com.thinkmorestupidless.ankka.http.Principal("m", roles = roles.toSet, issuer = Some(Routes.StaffIssuer))
    assertEquals(Routes.brandsOf(principal("affiliate_manager", "brand_goldvibe", "brand_silvervibe", "brand_")),
      Routes.BrandSet.Only(Set("goldvibe", "silvervibe")))
    assert(!Routes.brandsOf(principal("affiliate_manager")).holds("goldvibe"))
    assertEquals(Routes.brandsOf(principal("platform_admin")), Routes.BrandSet.Every)
    assert(Routes.brandsOf(principal("platform_admin")).holds("anything"))
  }
