package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.config.{ConfigChange, Configuration, CrmConfig, Secrets}
import io.eitheror.casino.engagement.profile.{PlayersFact, Profile, ProfileState}
import io.eitheror.casino.engagement.send.*

import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Try

/** research R4 and R5 against the fake Customer.io: the claim, each answer, the attempts, parking, suppression. */
class SendSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7", "PaymentFailed" -> "9"))

  override def beforeEach(context: BeforeEach): Unit =
    kit.crm.reset()
    kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)

  private def player(consented: Boolean = true): String =
    val id = s"p-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(60)
    apply(f.PlayerRegistered(id, at, None, None, None, Some("ZA"), Some("EUR"), None, at,
      email = Some(Personal(s"$id@example.com")), givenName = Some(Personal("Ada"))))
    if consented then apply(f.PlayerConsentChanged(id, "email", true, "registration", at))
    id

  private def apply(fact: f.CasinoPlayersFact): Unit =
    kit.components.forKeyValueEntity(EntityId(fact.playerId)).call(Profile.players).invoke(PlayersFact(fact)): Unit

  private def deposit(playerId: String, cls: String = "marketing", at: Instant = Instant.now()) =
    SendRequest(s"deposit_confirmed/${UUID.randomUUID()}", playerId, "deposit_confirmed", Some("deposit_confirmed"), cls,
      SendRequest.Crm, s"fact-${UUID.randomUUID()}", at, transactional = Some("DepositConfirmed"),
      data = Vector(DataField("amount", ProfileState.personal(playerId, "10.00"))))

  private def send(r: SendRequest) = kit.components.forWorkflow(EntityId(r.key))
  private def start(r: SendRequest): SendState = send(r).call(Send.start).invoke(r)
  private def state(r: SendRequest): SendState = send(r).call(Send.outcome).invoke()

  /** The send once it has ended or parked. */
  private def settled(r: SendRequest, within: FiniteDuration = 30.seconds): SendState =
    kit.eventually(s"send ${r.key} settled", within) {
      Option(state(r)).filter(s => s.ended || s.outcome == Outcome.Parked)
    }

  test("a fact's send identifies, tracks and sends the transactional, and ends accepted") {
    val p = player()
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals(s.outcome, Outcome.Accepted)
    assertEquals(s.calls.map(c => c.kind -> c.outcome), Vector("identify" -> "accepted", "track" -> "accepted", "transactional" -> "accepted"))
    assertEquals(kit.crm.identifies(p).size, 1)
    assertEquals(kit.crm.tracks(p).map(_.get("name").asText), Vector("deposit_confirmed"))
    // The raw body: the amount is a number written as the original wrote it, two decimals.
    assert(kit.crm.calls.exists(c => c.raw.contains("\"action\":\"event\"") && c.raw.contains("\"amount\":10.00")), kit.crm.calls.map(_.raw).mkString("\n"))
    val email = kit.crm.sends.head
    assertEquals((email.get("transactional_message_id").asText, email.get("to").asText), ("7", s"$p@example.com"))
    assertEquals(email.get("message_data").get("amount").asText, "10.00")
  }

  test("a repeat start answers the first outcome and calls nothing") {
    val p = player()
    val r = deposit(p)
    start(r)
    settled(r)
    val before = kit.crm.calls.size
    assertEquals(start(r).outcome, Outcome.Accepted)
    Thread.sleep(300)
    assertEquals(kit.crm.calls.size, before)
  }

  test("a 429 and a 503 are tried again, and the send is accepted at the third attempt") {
    val p = player()
    kit.crm.fail(429)
    kit.crm.fail(503)
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals(s.outcome, Outcome.Accepted)
    assertEquals(s.calls.find(_.kind == "identify").map(_.attempts), Some(3))
  }

  test("four failures park the send with the last answer, and nothing after it is called") {
    val p = player()
    kit.crm.fail(500, 2)
    kit.crm.fail(503, 2)
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals(s.outcome, Outcome.Parked)
    assertEquals(s.step, Some("identify"))
    assertEquals(s.calls.map(c => (c.kind, c.attempts, c.status)), Vector(("identify", 4, Some(503))))
    assert(s.parkedAt.isDefined)
    assertEquals(kit.crm.tracks(p), Vector.empty)
  }

  test("a timeout is retried as a failure") {
    val p = player()
    kit.crm.hangMillis = 2000
    kit.crm.hang(1)
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals(s.outcome, Outcome.Accepted)
    assertEquals(s.calls.find(_.kind == "identify").map(_.attempts), Some(2))
  }

  test("a 4xx is refused without a retry; a refused identify ends the send") {
    val p = player()
    kit.crm.refuse(400, """{"meta":{"error":"bad"}}""")
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals(s.outcome, Outcome.Refused)
    assertEquals(s.calls.map(c => (c.kind, c.attempts, c.status)), Vector(("identify", 1, Some(400))))
    assertEquals(kit.crm.calls.size, 1)
  }

  test("a refused track moves on to the transactional and the send ends refused") {
    val p = player()
    kit.crm.pass()
    kit.crm.refuse(400, """{"meta":{"error":"bad event"}}""")
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals(s.outcome, Outcome.Refused)
    assertEquals(s.calls.map(c => c.kind -> c.outcome), Vector("identify" -> "accepted", "track" -> "refused", "transactional" -> "accepted"))
  }

  test("an operator's retry and discard act on a parked send only") {
    val p = player()
    kit.crm.fail(503, 4)
    val r = deposit(p)
    start(r)
    assertEquals(settled(r).outcome, Outcome.Parked)
    val retried = send(r).call(Send.retry).invoke(Retry("op-1"))
    assertEquals(retried.outcome, Outcome.Pending)
    assertEquals(settled(r).outcome, Outcome.Accepted)
    assert(Try(send(r).call(Send.retry).invoke(Retry("op-1"))).isFailure)
    assert(Try(send(r).call(Send.discard).invoke(Discard("op-1", "no"))).isFailure)

    val r2 = deposit(p)
    kit.crm.fail(503, 4)
    start(r2)
    assertEquals(settled(r2).outcome, Outcome.Parked)
    val discarded = send(r2).call(Send.discard).invoke(Discard("op-1", "provider outage"))
    assertEquals((discarded.outcome, discarded.discardedBy), (Outcome.Discarded, Some("op-1")))
    assertEquals(ProfileState.reveal(discarded.discardReason), Some("provider outage"))
  }

  test("a retry of a parked send parks it again after one more failure") {
    val p = player()
    kit.crm.fail(503, 5)
    val r = deposit(p)
    start(r)
    assertEquals(settled(r).outcome, Outcome.Parked)
    send(r).call(Send.retry).invoke(Retry("op-1")): Unit
    val s = kit.eventually("parked again")(Option(state(r)).filter(s => s.outcome == Outcome.Parked && s.attemptsAt("identify") == 5))
    assertEquals(s.parkAt, 5)
  }

  test("a marketing send for a player with no consent identifies them and is refused as suppressed") {
    val p = player(consented = false)
    val r = deposit(p)
    start(r)
    val s = settled(r)
    assertEquals((s.outcome, s.reason), (Outcome.MarketingSuppressed, Some("consent_withdrawn")))
    assertEquals(kit.crm.identifies(p).size, 1)
    assertEquals(kit.crm.attributes(kit.crm.identifies(p).head).get("marketing_suppressed").map(_.asBoolean), Some(true))
    assertEquals((kit.crm.tracks(p), kit.crm.sends), (Vector.empty, Vector.empty))
    val record = kit.eventually("the refusal audited")(kit.facts(f.PlatformAudit).collectFirst {
      case a: f.AuditRecord if a.subjectId == p && a.action == "engagement.send.refused" => a
    })
    assertEquals(record.reasonCode, Some("marketing_suppressed"))
  }

  test("a service send for a suppressed player is made") {
    val p = player(consented = false)
    val r = deposit(p, cls = "service").copy(trigger = "payment_failed", event = Some("payment_failed"), transactional = Some("PaymentFailed"))
    start(r)
    assertEquals(settled(r).outcome, Outcome.Accepted)
    assertEquals(kit.crm.tracks(p).size, 1)
    assertEquals(kit.crm.sends.size, 1)
  }

  test("a closed player is refused every send, service class too, and the identify is still made") {
    val p = player()
    apply(f.PlayerClosed(p, "active", "closed", "player", None, None, Instant.now().minusSeconds(30)))
    val r = deposit(p, cls = "service")
    start(r)
    assertEquals(settled(r).outcome, Outcome.AccountClosed)
    assertEquals((kit.crm.identifies(p).size, kit.crm.tracks(p).size), (1, 0))
  }

  test("suppression is checked again at an operator's retry") {
    val p = player()
    kit.crm.fail(503, 4)
    val r = deposit(p)
    start(r)
    assertEquals(settled(r).outcome, Outcome.Parked)
    apply(f.PlayerSelfExcluded(p, "active", "self_excluded", "player", None, None, Instant.now(), None, Instant.now()))
    send(r).call(Send.retry).invoke(Retry("op-1")): Unit
    val s = kit.eventually("refused at the retry")(Option(state(r)).filter(_.ended))
    // The identify parked, so the retry makes it (identifies are always made) and the track is then refused.
    assertEquals((s.outcome, s.reason), (Outcome.MarketingSuppressed, Some("self_excluded")))
  }

  test("a stale fact is skipped and calls nothing") {
    val p = player()
    val r = deposit(p, at = Instant.now().minus(Duration.ofHours(25)))
    assertEquals(start(r).outcome, Outcome.SkippedStale)
    Thread.sleep(300)
    assertEquals(kit.crm.calls, Vector.empty)
  }

  test("a route's trigger uses the fallback for the verification and is not configured for the others") {
    val p = player()
    def route(trigger: String) = SendRequest(s"$trigger/${UUID.randomUUID()}", p, trigger, None, "service", SendRequest.Email,
      "", Instant.now(), transactional = Some(trigger), recipient = Some(ProfileState.personal(p, "new@example.com")),
      data = Vector(DataField("verification_link", ProfileState.personal(p, "https://x/verify"))), calls = Vector(Kind.Transactional))
    val verification = route("EmailAddressVerification")
    start(verification)
    assertEquals(settled(verification).outcome, Outcome.Accepted)
    val email = kit.crm.sends.head
    assertEquals((email.get("transactional_message_id").asText, email.get("to").asText), ("email_address_verification", "new@example.com"))
    assertEquals(email.get("message_data").get("brand_name").asText, kit.slug)
    val other = route("PasswordChangeVerification")
    start(other)
    assertEquals(settled(other).outcome, Outcome.NoConfiguration)
    assertEquals(kit.crm.sends.size, 1)
  }

  test("a player with no address is no_address and nothing is sent") {
    val id = s"p-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(60)
    apply(f.PlayerRegistered(id, at, None, None, None, Some("ZA"), Some("EUR"), None, at))
    apply(f.PlayerConsentChanged(id, "email", true, "registration", at))
    val r = deposit(id, cls = "service")
    start(r)
    assertEquals(settled(r).outcome, Outcome.NoAddress)
    assertEquals(kit.crm.sends, Vector.empty)
  }

  test("a brand with no site is no_configuration") {
    val slug = kit.slug
    val was  = kit.components.forKeyValueEntity(EntityId(slug)).call(Configuration.get).invoke()
    kit.components.forKeyValueEntity(EntityId(slug)).call(Configuration.put).invoke(ConfigChange(CrmConfig(), "kit")): Unit
    try
      val r = deposit(player())
      start(r)
      assertEquals(settled(r).outcome, Outcome.NoConfiguration)
      assertEquals(kit.crm.calls, Vector.empty)
    finally kit.components.forKeyValueEntity(EntityId(slug)).call(Configuration.put).invoke(ConfigChange(was, "kit")): Unit
  }

  test("a key rotated between attempts is read at the next attempt") {
    val p = player()
    kit.crm.hangMillis = 1500
    kit.crm.hang(1)
    val r = deposit(p)
    start(r)
    kit.eventually("the first attempt made")(kit.crm.calls.headOption): Unit
    kit.crm.expect(FakeCrm.SiteId, "rotated-track-key", FakeCrm.AppKey)
    kit.secrets.put(Secrets.track(kit.slug), "rotated-track-key")
    try assertEquals(settled(r).outcome, Outcome.Accepted)
    finally
      kit.secrets.put(Secrets.track(kit.slug), FakeCrm.TrackKey)
      kit.crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)
  }

  test("a restart mid-attempt resumes the send") {
    val p = player()
    kit.crm.hangMillis = 3000
    kit.crm.hang(1)
    val r = deposit(p)
    start(r)
    kit.eventually("the first attempt made")(kit.crm.calls.headOption): Unit
    kit.restartService()
    assertEquals(settled(r, 60.seconds).outcome, Outcome.Accepted)
  }
