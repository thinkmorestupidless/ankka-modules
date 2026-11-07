package io.eitheror.casino.engagement.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.money.{MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.engagement.profile.Attributes

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * FR-008 through the broker: a player's life fact by fact, each published once the last one's identify has arrived, and
 * the identify read at the fake by attribute name and JSON type.
 */
class IdentifySuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit
  private val eur      = io.eitheror.casino.contract.money.Currency.unsafe("EUR")

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  private def attrs(b: JsonNode) = kit.crm.attributes(b)

  /** The latest identify of the player satisfying `p` among those made after `publish`. */
  private def identified(playerId: String)(publish: => Unit)(p: Map[String, JsonNode] => Boolean): Map[String, JsonNode] =
    val before = kit.crm.identifies(playerId).size
    publish
    kit.eventually(s"an identify of $playerId")(kit.crm.identifies(playerId).drop(before).map(attrs).findLast(p))

  private def text(a: Map[String, JsonNode], k: String) = a.get(k).map(_.asText)

  private def players(fact: f.CasinoPlayersFact): Unit = kit.publish(f.CasinoPlayers, fact.key, fact)
  private def money(fact: f.MoneyTransactionsFact): Unit = kit.publish(f.MoneyTransactions, fact.key, fact)

  test("a life fact by fact: every identify precedes its tracks; every attribute by name and type; balances absent until a movement") {
    kit.crm.reset()
    val p  = s"id-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(600)
    val registered = identified(p) {
      players(f.PlayerRegistered(p, at, None, None, None, Some("ZA"), Some("EUR"), Some("bronze"), at,
        email = Some(Personal(s"$p@example.com")), givenName = Some(Personal("Ada"))))
    }(a => text(a, "id").contains(p))
    for k <- Vector("real_money", "bonus_money", "total_balance", "deposit_count", "total_deposit_amount") do
      assert(!registered.contains(k), s"$k before any movement: $registered")

    identified(p) {
      players(f.PlayerConsentChanged(p, "email", true, "registration", at.plusSeconds(1)))
    }(a => text(a, "email_subscribed").contains("true")): Unit
    identified(p) {
      players(f.PlayerLoggedIn(p, "s-1", at.plusSeconds(180)))
    }(_.contains("last_login_at")): Unit
    identified(p) {
      money(f.DepositConfirmed(p, eur, "tx-1", MinorUnits.unsafe(2500L), "ref-1", 1, SignedMinorUnits(2500L), MinorUnits.unsafe(0L),
        at.plusSeconds(181)))
    }(_.contains("deposit_count")): Unit
    identified(p) {
      money(f.BetPlaced(p, eur, "tx-2", "r-1", "g-1", MinorUnits.unsafe(100L), "real", SignedMinorUnits(2400L), MinorUnits.unsafe(50L),
        at.plusSeconds(182)))
    }(a => a.get("real_money").exists(_.decimalValue.compareTo(BigDecimal("24.00").bigDecimal) == 0)): Unit
    identified(p) {
      players(f.RgLimitApplied(p, "DEPOSIT_DAILY", Some("DAILY"), Some(10000L), "set", at.plusSeconds(183)))
    }(a => text(a, "has_rg_limits").contains("true")): Unit
    val last = identified(p) {
      players(f.PlayerConsentChanged(p, "email", false, "preferences", at.plusSeconds(184)))
    }(a => text(a, "email_subscribed").contains("false"))

    // The whole profile, by name and type.
    val expected = Set("id", "created_at", "email", "player_name", "brand_id", "currency", "country", "registration_date",
      "vip_tier", "last_login_at", "last_deposit_at", "total_deposit_amount", "has_rg_limits", "rg_limit_types",
      "account_status", "deposit_count", "daily_deposit_count", "real_money", "bonus_money", "total_balance",
      "email_subscribed", "sms_subscribed", "consent_source", "pot_vip", "marketing_suppressed", "suppression_reason")
    assertEquals(last.keySet -- expected, Set.empty[String])
    assertEquals(expected -- last.keySet, Set.empty[String])
    for (k, v) <- last do
      if Attributes.numeric(k) then assert(v.isNumber, s"$k should be a number: $v")
      else assert(v.isTextual, s"$k should be a string: $v")
    assertEquals(last("total_balance").decimalValue.compareTo(BigDecimal("24.50").bigDecimal), 0)
    assertEquals(text(last, "rg_limit_types"), Some("DEPOSIT_DAILY"))
    assert(!last.contains("email_consent_at"), "a withdrawn consent sends no consent time")

    // Every track of the player comes after at least as many identifies as tracks so far.
    val mine = kit.crm.calls.filter(_.raw.contains(p)).map(c => c.raw.contains("\"identify\""))
    mine.indices.filterNot(mine).foreach { i =>
      val before = mine.take(i + 1)
      assert(before.count(identity) >= before.count(!_), s"a track before its identify at call $i")
    }
  }

  test("a tier change identifies with the new value and tracks nothing") {
    kit.crm.reset()
    val p  = s"id-${UUID.randomUUID().toString.take(8)}"
    val at = Instant.now().minusSeconds(600)
    identified(p) {
      players(f.PlayerRegistered(p, at, None, None, None, Some("ZA"), Some("EUR"), Some("bronze"), at,
        email = Some(Personal(s"$p@example.com"))))
    }(a => text(a, "id").contains(p)): Unit
    val tracksBefore = kit.crm.tracks(p).size
    identified(p) {
      players(f.VipTierChanged(p, Some("bronze"), "gold", "op-1", at.plusSeconds(1)))
    }(a => text(a, "vip_tier").contains("gold")): Unit
    Thread.sleep(1500)
    assertEquals(kit.crm.tracks(p).size, tracksBefore)
  }
