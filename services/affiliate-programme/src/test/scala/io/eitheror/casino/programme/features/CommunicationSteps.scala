package io.eitheror.casino.programme.features

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.Affiliate
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.it.{Messaging, Reply, Scene}
import io.eitheror.casino.programme.postbacks.{OutcomeState, PostbackOutcome, PostbackRules}

import java.time.Duration
import scala.jdk.CollectionConverters.*

/**
 * features/programme/communications.feature (US7). Postbacks go out from goldvibe's facts; the notices
 * are sent at a brand of the scenario's own, which has every message configured.
 */
class CommunicationSteps extends ProgrammeFeatures("communications.feature"):
  private var scene: Scene   = null
  private var brand: String  = null
  private var email: String  = null
  private var aff: String    = null
  private var kind: String   = null
  private var answered: Reply = null
  private var others: Vector[String] = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    scene = null; brand = null; email = null; aff = null; kind = null; answered = null; others = Vector.empty

  private def items(r: Reply): Vector[JsonNode] = { assertEquals(r.status, 200, r.text); Option(r.json).toVector.flatMap(_.elements().asScala) }

  // ── postbacks ───────────────────────────────────────────────────────────────────────────────────

  Given("an affiliate with a postback address") { () =>
    scene = Scene(kit, planType = Plan.Hybrid, cpa = 5_000, revShareBp = 3_000)
    Messaging.postbackTo(kit, scene.affiliate)
  }
  When("a registration, first deposit, deposit or commission fact occurs for their player") { () =>
    val f = scene.facts.copy(clickId = Some(id("click")))
    val p = kit.fresh("ps")
    Seq(f.registration(p, kit.now), f.firstDeposit(p, 2_000, kit.now), f.deposit(p, 2_000, kit.now),
      f.ggrDaily(p, kit.now.toString.take(10), 10_000, 0, kit.now)).foreach(fact => scene.send(fact): Unit)
  }
  Then("a postback is sent with the kind, the click id, the payout amount and the status substituted") { () =>
    val calls = kit.eventually("four postbacks")(Option(kit.postbacks.calls).filter(_.size == 4)).map(_.query)
    assertEquals(calls.map(_("event")).toSet, Set("registration", "ftd", "deposit", "revshare"))
    assert(calls.forall(_("click") == ids("click")), calls.mkString)
    val ftd = calls.find(_("event") == "ftd").get
    assertEquals((ftd("payout"), ftd("status")), ("50.00", "approved"))
  }

  private def outcome: OutcomeState =
    kit.components.forKeyValueEntity(PostbackOutcome.entityId(scene.affiliate, scene.brand, "registration")).call(PostbackOutcome.get).invoke()

  Given("a postback that failed") { () =>
    scene = Scene(kit)
    Messaging.postbackTo(kit, scene.affiliate)
    kit.postbacks.default = 503
    scene.player(): Unit
    kit.eventually("the first attempt failed")(Option(outcome).filter(o => o.attempts == 1 && o.lastStatus.contains(503))): Unit
    ids("start") = kit.now.toString
  }
  When("it is retried") { () =>
    var last = outcome
    while last.status != PostbackRules.Undelivered do
      val attempts = last.attempts
      kit.moveClock(kit.now.plus(PostbackRules.next(attempts)).plusSeconds(1))
      last = kit.eventually(s"attempt ${attempts + 1}")(Option(outcome).filter(_.attempts > attempts))
  }
  Then("it is retried with backoff for up to {string} hours") { (hours: String) =>
    val start = java.time.Instant.parse(ids("start"))
    val calls = kit.postbacks.calls.size
    assertEquals(calls, 28)
    assert(!kit.now.isBefore(start.plus(Duration.ofHours(hours.toLong))))
    assert(kit.now.isBefore(start.plus(Duration.ofHours(hours.toLong + 1))))
  }
  Then("the last outcome is visible to the affiliate and to a manager") { () =>
    def read(r: => Reply) = kit.eventually("the outcome", scala.concurrent.duration.Duration(60, "s"))(
      items(r).find(o => o.get("event").asText == "registration" && o.get("status").asText == "undelivered"))
    read(kit.request("GET", s"/v1/my/${scene.affiliate}/postbacks", headers = kit.asAffiliate(scene.affiliate))): Unit
    read(kit.request("GET", s"/v1/affiliates/goldvibe/accounts/${scene.affiliate}/view/postbacks", headers = kit.asManager())): Unit
  }

  // ── notifications and emails ────────────────────────────────────────────────────────────────────

  Given("an affiliate") { () =>
    brand = Messaging.brand(kit)
    email = s"${id("aff")}@example.com"
    // The first attempt at every send fails, and the next succeeds: what is asked must be done all the same.
    kit.customerIo.failFirst = true
  }

  private def applied(): Unit =
    val r = Messaging.apply(kit, brand, email)
    assertEquals(r.status, 201, r.text)
    aff = r.json.get("affiliateId").asText

  private def approved(): Unit =
    applied()
    Messaging.told(kit, aff, "APPLICATION_SUBMITTED"): Unit
    answered = Messaging.approve(kit, brand, aff)

  When("their application is received") { () =>
    val r = Messaging.apply(kit, brand, email)
    answered = r
    aff = r.json.get("affiliateId").asText
    kind = "APPLICATION_SUBMITTED"; ids("email") = "AffiliateApplicationReceived"
  }
  When("their application is approved") { () =>
    approved()
    kind = "APPLICATION_APPROVED"; ids("email") = "AffiliateApplicationApproved"
  }
  When("their application is rejected") { () =>
    applied()
    answered = kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/reject", Some("""{"reason":"no site"}"""), kit.asManager(Seq(brand)))
    kind = "APPLICATION_REJECTED"; ids("email") = "AffiliateApplicationRejected"
  }
  When("their payout is paid") { () =>
    aff = kit.affiliate(id("payee"), brand, kit.plan(brand, kit.fresh("p"), Plan.Cpa, cpa = 1_000), email = Some(email))
    val mgr = kit.asManager(Seq(brand))
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/adjustments",
      Some("""{"type":"CREDIT","amount":5000,"currency":"EUR","reason":"bonus"}"""), mgr).status, 201)
    kit.owed(aff, brand, "EUR")
    val period = kit.now.toString.take(7)
    assertEquals(kit.request("POST", s"/v1/affiliates/$brand/payouts/close?period=$period", headers = mgr).status, 200)
    kit.request("PATCH", s"/v1/affiliates/$brand/payouts/$aff:EUR:$period/status", Some("""{"status":"APPROVED"}"""), mgr): Unit
    answered = kit.request("PATCH", s"/v1/affiliates/$brand/payouts/$aff:EUR:$period/status", Some("""{"status":"PAID"}"""), mgr)
    kind = "PAYOUT_PAID"; ids("email") = "AffiliatePaymentProcessed"
  }
  When("they are invited") { () =>
    approved()
    kit.eventually("the realm user")(kit.components.forEventSourcedEntity(EntityId(aff)).call(Affiliate.get).invoke().keycloakUser): Unit
    answered = kit.request("POST", s"/v1/affiliates/$brand/accounts/$aff/resend-invitation", headers = kit.asManager(Seq(brand)))
    kind = "INVITED"; ids("email") = "AffiliateInvite"
  }
  When("they ask for a password reset") { () =>
    approved()
    answered = kit.request("POST", s"/v1/affiliates/$brand/forgot-password", Some(s"""{"email":"$email"}"""), kit.fromInternet)
    kind = "PASSWORD_RESET_REQUESTED"; ids("email") = "AffiliatePasswordReset"
  }
  Then("an in-portal notification is written") { () => Messaging.told(kit, aff, kind): Unit }
  Then("a transactional email is sent with the brand's sender configuration") { () =>
    // The first attempt failed; the retry delivers it (the kit's waits between attempts are seconds).
    kit.eventually("the failed first attempt")(kit.customerIo.to(email).find(s => s.transactionalId == s"tid-${ids("email")}" && !s.delivered)): Unit
    val sent = Messaging.emailed(kit, email, ids("email"))
    assertEquals(sent.apiKey, "cio-key")
  }
  Then("a failed send never blocks the action") { () =>
    assert(answered.status / 100 == 2, answered.text)
    val attempts = kit.eventually("a failed attempt, then a delivered one")(Option(kit.customerIo.to(email))
      .filter(a => a.exists(!_.delivered) && a.exists(_.delivered)))
    assert(attempts.indexWhere(!_.delivered) < attempts.indexWhere(_.delivered), attempts.toString)
  }

  // ── a parent, and marking read ──────────────────────────────────────────────────────────────────

  Given("a sub who applies under a parent") { () =>
    aff = kit.affiliate(id("parent"), "goldvibe", kit.plan("goldvibe", kit.fresh("p")))
    ids("token") = kit.request("POST", s"/v1/my/$aff/invite-links?brand=goldvibe", Some("{}"), kit.asAffiliate(aff)).json.get("token").asText
  }
  When("the application is recorded") { () =>
    answered = kit.request("POST", s"/v1/join/${ids("token")}?brandId=goldvibe", Some(s"""{"email":"${id("sub")}@example.com"}"""), kit.fromInternet)
    assertEquals(answered.status, 201, answered.text)
  }
  Then("the parent is told that an approval is pending") { () => Messaging.told(kit, aff, "SUB_AFFILIATE_PENDING"): Unit }

  Given("an affiliate with notifications") { () =>
    brand = Messaging.brand(kit)
    val all = Seq("one", "two").map { n =>
      val r = Messaging.apply(kit, brand, s"${id(n)}@example.com")
      assertEquals(r.status, 201, r.text)
      r.json.get("affiliateId").asText
    }
    all.foreach(a => Messaging.told(kit, a, "APPLICATION_SUBMITTED"): Unit)
    aff = all.head
    others = all.tail.toVector
  }
  When("they read them or mark all read") { () =>
    assertEquals(items(kit.request("GET", s"/v1/my/$aff/notifications", headers = kit.asAffiliate(aff))).size, 1)
    answered = kit.request("PATCH", s"/v1/my/$aff/notifications/read-all", headers = kit.asAffiliate(aff))
    assertEquals(answered.json.get("marked").asInt, 1)
  }
  Then("the notifications are marked read for that affiliate only") { () =>
    kit.eventually("read")(Option(Messaging.notifications(kit, aff)).filter(ns => ns.nonEmpty && ns.forall(_.get("read").asBoolean))): Unit
    others.foreach(o => assert(Messaging.notifications(kit, o).forall(!_.get("read").asBoolean)))
  }
