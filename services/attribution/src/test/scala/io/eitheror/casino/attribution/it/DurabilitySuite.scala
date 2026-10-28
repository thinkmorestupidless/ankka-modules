package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.{AttributionService, Json}
import io.eitheror.casino.attribution.carriers.{Delivery, DeliveryRules}
import io.eitheror.casino.attribution.money.DayCloser
import io.eitheror.casino.attribution.networks.{DocumentRefresh, Documents, NetworkDocuments}
import io.eitheror.casino.attribution.settings.SettingsEntity
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.DriverManager
import java.time.LocalTime
import scala.concurrent.duration.*

/**
 * Durability over a restart (T068): a binding, a deposit, an open day, an undelivered delivery and a cached
 * key document survive the runtime stopped and started on the same database. Afterwards the day closes by
 * its timer once the clock passes the close, the delivery is listed and resends, the document is still
 * held, each recurring timer exists once, the first day is unchanged and the click salt is not remade.
 * A kit of its own: it restarts the service and moves the clock a day.
 */
class DurabilitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 600.seconds

  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k

  override def afterAll(): Unit = kit.stop()

  private def webhooks = s"/v1/casino/backoffice/${kit.brand}/attribution-webhooks"
  private def delivery(sub: String, factId: String) =
    kit.components.forWorkflow(Delivery.id(sub, factId)).call(Delivery.status).invoke()
  private def settings = kit.components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.get).invoke()
  private def keyDocument =
    kit.components.forKeyValueEntity(EntityId("programme")).call(NetworkDocuments.get).invoke().of(Documents.Kind.Keys)

  /** The rows of each recurring timer in the runtime's table. */
  private def timerRows(name: String): Int =
    val c = DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val s = c.prepareStatement("SELECT count(*) FROM ankka_timers WHERE timer_name = ?")
      s.setString(1, name)
      val rs = s.executeQuery()
      rs.next()
      rs.getInt(1)
    finally c.close()

  test("a binding, a deposit, an open day, an undelivered delivery and a cached document survive a restart") {
    val p = (player: String) => Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)

    // An undelivered delivery: the first post fails, and the retry a day later gives up.
    kit.receiver.default = Some(503)
    val made = kit.request("POST", webhooks, Some(Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl,
      "secret" -> kit.receiver.secret))), kit.asOperator())
    assertEquals(made.status, 201, made.text)
    val sub = made.json.get("id").asText
    kit.eventually("the subscription listed")(Option.when(kit.request("GET", s"$webhooks?network=programme",
      headers = kit.asOperator()).text.contains(sub))(())): Unit
    val early   = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"))))
    val earlyId = s"registration/${p(early)}"
    kit.eventually("the first attempt")(Option.when(delivery(sub, earlyId).attempts >= 1)(())): Unit
    kit.clock.moveBy(25.hours)
    kit.eventually("undelivered", 60.seconds)(Option.when(delivery(sub, earlyId).status == DeliveryRules.Undelivered)(())): Unit

    // A binding, a deposit and an open day.
    val player = kit.bindPlayer(Some(kit.network.mint("aff-1", kit.fresh("lnk"), Some("autumn"), Vector("s1"))), clickId = Some("c-dur"))
    val binding = kit.eventually("bound")(kit.record(player).binding)
    kit.publish(PaymentsPayments, PaymentCaptured(kit.fresh("pay"), player, "psp", MinorUnits.unsafe(5000), Currency.unsafe("EUR"), kit.now))
    val deposited = kit.eventually("the first deposit")(kit.record(player).firstDepositAt)
    val days   = DayClock(kit)
    val day    = days.evening()
    val wallet = WalletScript(kit, player)
    wallet.deposit(5000)
    wallet.bet("r-1", 700)
    wallet.win("r-1", 300)
    days.played(wallet, day)

    kit.refreshDocuments(): Unit
    val document = keyDocument.getOrElse(fail("no key document held"))
    val firstDay = settings.firstDay
    val salt     = kit.testKit.secrets.get(AttributionService.ClickSalt)
    assert(salt.isDefined && firstDay.isDefined)
    val fetchesBefore = kit.network.received.size

    kit.testKit.restartService()
    AttributionService.start(kit.testKit.service, kit.timers, kit.settings, kit.clock)

    assertEquals(kit.record(player).binding, Some(binding))
    assertEquals(kit.record(player).firstDepositAt, Some(deposited))
    assertEquals(keyDocument.flatMap(_.fetchedAt), document.fetchedAt)
    assertEquals(kit.network.received.size, fetchesBefore, "the held document is not fetched again at start")
    assertEquals(settings.firstDay, firstDay)
    assertEquals(kit.testKit.secrets.get(AttributionService.ClickSalt), salt)
    assertEquals((timerRows(DayCloser.TimerName), timerRows(DocumentRefresh.TimerName)), (1, 1))

    // The day closes by its timer once the clock passes the close.
    days.at(day.plusDays(1), LocalTime.of(0, 21))
    val daily = kit.eventually("the ggr-daily after the restart", 60.seconds)(kit.factsOn("programme").collectFirst {
      case d: GgrDaily if d.pseudonym == p(player) && d.reportingDay == day.toString => d
    })
    assertEquals((daily.stake.value, daily.win.value, daily.ggr.value), (700L, 300L, 400L))

    // The delivery is still listed, and resends.
    val listed = kit.eventually("still listed")(Option(kit.request("GET", s"$webhooks/undelivered?network=programme",
      headers = kit.asOperator())).filter(_.text.contains(earlyId)))
    assertEquals(listed.status, 200)
    kit.receiver.default = Some(200)
    val resent = kit.request("POST", s"$webhooks/undelivered/${URLEncoder.encode(s"$sub/$earlyId", UTF_8)}/resend", headers = kit.asOperator())
    assertEquals(resent.status, 200, resent.text)
    kit.eventually("delivered after the restart")(Option.when(delivery(sub, earlyId).status == DeliveryRules.Delivered)(())): Unit
  }
