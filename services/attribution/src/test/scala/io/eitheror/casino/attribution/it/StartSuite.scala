package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.attribution.{AttributionService, Settings}
import io.eitheror.casino.attribution.inputs.RewardsFacts
import io.eitheror.casino.attribution.money.{DayCloser, Days}
import io.eitheror.casino.attribution.networks.DocumentRefresh
import io.eitheror.casino.attribution.settings.SettingsEntity
import io.eitheror.casino.contract.facts.{BonusCodeEntered, CasinoRewards}

import scala.concurrent.duration.*

/**
 * The service starts (T033): the brand's first day, both recurring timers, the click salt in the store,
 * and every route of contracts/routes.md answering `501` or its ACL's refusal.
 */
class StartSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 300.seconds

  private var kit: Kit = scala.compiletime.uninitialized
  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def brand = kit.brand
  private val Attribution = "/v1/casino/brands/goldvibe/attribution"
  private val Backoffice  = "/v1/casino/backoffice/goldvibe/attribution"
  private val Webhooks    = "/v1/casino/backoffice/goldvibe/attribution-webhooks"

  test("the brand's first day is today in the reporting zone") {
    val s = kit.components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke()
    assertEquals(s.firstDay, Some(Days.dayOf(kit.now, kit.zone)))
  }

  test("both recurring timers are set, at their periods") {
    val close   = kit.eventually("close-days")(kit.probe.scheduled(DayCloser.TimerName))
    val refresh = kit.eventually("refresh-documents")(kit.probe.scheduled(DocumentRefresh.TimerName))
    assertEquals(close.period, Some(5.minutes))
    assertEquals(refresh.period, Some(1.minute))
  }

  test("the click salt is in the store, and a second start keeps it") {
    val salt = kit.testKit.secrets.get(AttributionService.ClickSalt)
    assert(salt.exists(_.nonEmpty), "no click salt")
    AttributionService.started(kit.components, kit.testKit.secrets, kit.timers.timerScheduler, kit.settings, kit.now)
    assertEquals(kit.testKit.secrets.get(AttributionService.ClickSalt), salt)
  }

  /** Every route of routes.md, each with a caller its ACL admits: its handler answers (501 until its story builds it). */
  private def admitted: Seq[(String, String, Seq[(String, String)])] =
    val op = kit.asOperator()
    Seq(
      ("POST", "/v1/track/click", kit.fromInternet),
      ("GET", s"$Attribution/keys", kit.fromInternet),
      ("GET", s"$Attribution/networks/programme/feed", kit.asMachine("acme", "reader")),
      ("POST", s"$Attribution/players/p-1/erasure", kit.asService()),
      ("GET", s"$Backoffice/settings", op), ("PUT", s"$Backoffice/settings", op), ("GET", s"$Backoffice/status", op),
      ("GET", s"$Backoffice/networks", op), ("POST", s"$Backoffice/networks", op), ("GET", s"$Backoffice/networks/n1", op),
      ("PUT", s"$Backoffice/networks/n1", op), ("DELETE", s"$Backoffice/networks/n1", op),
      ("POST", s"$Backoffice/networks/n1/pseudonym-key", op), ("GET", s"$Backoffice/records/p-1", op),
      ("GET", s"$Backoffice/signing-keys", op), ("POST", s"$Backoffice/signing-keys", op),
      ("DELETE", s"$Backoffice/signing-keys/k1", op),
      ("GET", Webhooks, op), ("POST", Webhooks, op), ("GET", s"$Webhooks/undelivered", op),
      ("POST", s"$Webhooks/undelivered/w1%2Fclick%2Fc-1/resend", op), ("POST", s"$Webhooks/undelivered/w1%2Fclick%2Fc-1/discard", op),
      ("GET", s"$Webhooks/w1", op), ("PUT", s"$Webhooks/w1", op), ("DELETE", s"$Webhooks/w1", op)
    )

  private def bodyFor(method: String) = if method == "POST" || method == "PUT" then Some("{}") else None

  test("every route admits a caller its ACL admits: no 401, 403 or 404 from the route table") {
    val answers = admitted.map((m, p, h) => (m, p, kit.request(m, p, bodyFor(m), h).status))
    assertEquals(answers.filter(a => a._3 == 401 || a._3 == 403 || a._3 == 405), Seq.empty)
  }

  test("each ACL refuses what it does not admit") {
    def status(m: String, p: String, h: Seq[(String, String)]) = kit.request(m, p, bodyFor(m), h).status
    // an operator route without a token
    assertEquals(status("GET", s"$Backoffice/settings", kit.fromInternet), 401)
    assertEquals(status("GET", Webhooks, kit.fromInternet), 401)
    // an operator of another brand
    assertEquals(status("GET", s"$Backoffice/settings", kit.asOperator(roles = "brand_other")), 403)
    // the feed under a service caller, and from the internet with no machine token
    assertEquals(status("GET", s"$Attribution/networks/programme/feed", kit.asService()), 403)
    assertEquals(status("GET", s"$Attribution/networks/programme/feed", kit.fromInternet), 403)
    // the erasure under an operator
    assertEquals(status("POST", s"$Attribution/players/p-1/erasure", kit.asOperator()), 403)
  }

  test("a brand without the casino module does not read casino.rewards") {
    val without = AttributionService.descriptors(Settings(brand, modules = Set.empty))
    val rewards = RewardsFacts.descriptor.componentId
    assert(!without.exists(_.componentId == rewards))
    assert(AttributionService.descriptors(kit.settings).exists(_.componentId == rewards))
    val second = Kit(modules = Set.empty)
    try
      // casino.rewards is never read: a code entered for a registered player leaves no touch
      val player = second.bindPlayer(None)
      second.eventually("registered")(Some(second.record(player)).filter(_.registeredAt.isDefined))
      second.publish(CasinoRewards, BonusCodeEntered(player, "ANYCODE", second.now))
      Thread.sleep(3000)
      assertEquals(second.record(player).touches, Vector.empty)
    finally
      second.stop()
      kit.rewire()
  }
