package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.Json.*
import io.eitheror.casino.attribution.record.Touches
import io.eitheror.casino.contract.facts.{BonusCodeEntered, CasinoRewards}

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/** The operator's windows (FR-007, FR-008; T044): read, set, refused out of range, and the code window applied. */
class WindowsSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  private val Path = "/v1/casino/backoffice/goldvibe/attribution/settings"

  private def put(attribution: Int, code: Int) =
    kit.request("PUT", Path, Some(s"""{"attributionWindowDays":$attribution,"bonusCodeWindowDays":$code}"""), kit.asOperator())

  override def afterAll(): Unit = put(30, 30): Unit

  test("the defaults read back as 30 and 30") {
    val r = kit.request("GET", Path, None, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    assertEquals(r.json, Json.parse("""{"attributionWindowDays":30,"bonusCodeWindowDays":30}""").get)
  }

  test("0 and 366 are refused 400 INVALID_REQUEST naming the field") {
    val zero = put(0, 30)
    assertEquals((zero.status, zero.json.textOr("code", ""), zero.json.textOr("field", "")), (400, "INVALID_REQUEST", "attributionWindowDays"))
    val big = put(30, 366)
    assertEquals((big.status, big.json.textOr("field", "")), (400, "bonusCodeWindowDays"))
  }

  test("a bonus code window of 7 days: a code entered 8 days after registration is a touch") {
    assertEquals(put(30, 7).status, 200)
    val code = kit.fresh("WIN").toUpperCase
    kit.network.codes = kit.network.codes.updated(code, "offer" -> kit.network.mint("aff-7", kit.fresh("lnk")))
    kit.moveClock(kit.now.plusSeconds(kit.network.cacheSeconds + 1))
    kit.refreshDocuments(): Unit
    val player = kit.bindPlayer(None, at = kit.now.minus(JDuration.ofDays(8)))
    kit.eventually("registered")(Some(kit.record(player)).filter(_.registeredAt.isDefined))
    kit.publish(CasinoRewards, BonusCodeEntered(player, code, kit.now))
    val r = kit.eventually("the touch")(Some(kit.record(player)).filter(_.touches.nonEmpty))
    assertEquals((r.binding, r.touches.map(_.outcome)), (None, Vector(Touches.Later)))
  }
