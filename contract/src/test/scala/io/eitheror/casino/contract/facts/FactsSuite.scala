package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.fasterxml.jackson.databind.ObjectMapper
import io.eitheror.casino.contract.Layout

import scala.jdk.CollectionConverters.*

class FactsSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()

  /** Encodes a family's sample through its topic's codec, as a producer would. */
  private def encoded[F <: Fact](topic: Topic[F], family: FamilyDef[?]): com.fasterxml.jackson.databind.JsonNode =
    mapper.readTree(writeToString(family.sample.asInstanceOf[F])(using topic.codec))

  for topic <- Topics.all; family <- topic.families do
    test(s"${topic.name} ${family.name}: the codec writes the family as `type` and exactly the schema's fields, and reads it back") {
      val t = topic.asInstanceOf[Topic[Fact]]
      val json = encoded(t, family)
      assertEquals(json.get("type").asText(), family.name)
      val written = json.fieldNames().asScala.toSet - "type"
      assertEquals(written, family.fields.map(_._1).toSet)
      val back = readFromString[Fact](mapper.writeValueAsString(json))(using t.codec)
      assertEquals(back, family.sample)
    }

  test("every brand topic of the layout has its facts, in FR-011's order") {
    assertEquals(Topics.brand.map(_.name), Layout.BrandTopics)
  }

  test("every family name is unique within its topic") {
    Topics.all.foreach(t => assertEquals(t.families.map(_.name).distinct.size, t.families.size, t.name))
  }

  test("the key is the entity the fact is about (FR-012)") {
    assertEquals(MoneyTransactions.families.head.sample.asInstanceOf[Fact].key, "x/EUR")
    assertEquals(CasinoPlayers.families.head.sample.asInstanceOf[Fact].key, "x")
    assertEquals(PaymentsPayments.families.head.sample.asInstanceOf[Fact].key, "x")
  }

  test("a fact about a player carries player_id, except attribution, which never does (FR-012)") {
    for topic <- Topics.brand; family <- topic.families do
      val has = family.fields.exists(_._1 == "player_id")
      if topic.name == "affiliates.attribution" then assert(!has, family.name)
      else if topic.name != "platform.audit" then assert(has, s"${topic.name} ${family.name}")
  }

  test("the original's export fields are kept (FR-014)") {
    def fields(topic: Topic[?], family: String) = topic.families.find(_.name == family).get.fields.map(_._1).toSet
    assert(Set("amount_cents", "currency", "transaction_id").subsetOf(fields(MoneyTransactions, "deposit_confirmed")))
    assert(Set("btag", "country", "currency", "vip_tier", "registered_at").subsetOf(fields(CasinoPlayers, "player_registered")))
    assert(Set("previous_value", "new_value", "actor", "actor_id", "reason").subsetOf(fields(CasinoPlayers, "player_suspended")))
    assert(Set("rg_case_id", "case_type", "closes_at").subsetOf(fields(CasinoPlayers, "rg_case_opened")))
    assert(Set("payout_id", "amount_cents", "currency", "failure_reason").subsetOf(fields(CasinoWithdrawals, "withdrawal_failed")))
    assert(Set("round_id", "game_id", "stake_cents", "win_cents", "net_result_cents", "currency").subsetOf(fields(CasinoRounds, "round_settled")))
    assert(Set("bonus_id", "bonus_name", "amount_cents", "currency").subsetOf(fields(CasinoRewards, "bonus_granted")))
    assert(Set("previous_tier", "new_tier").subsetOf(fields(CasinoPlayers, "vip_tier_changed")))
    assert(Set("session_id", "game_id", "duration_minutes").subsetOf(fields(CasinoSessions, "game_session_ended")))
  }

  test("every money family carries the balances after the movement") {
    MoneyTransactions.families.foreach { f =>
      assert(Set("real_balance_after", "restricted_balance_after").subsetOf(f.fields.map(_._1).toSet), f.name)
    }
  }

  test("an amount is written as a whole number, never a decimal") {
    val json = writeToString(MoneyTransactions.families.head.sample.asInstanceOf[MoneyTransactionsFact])(using MoneyTransactions.codec)
    assert(json.contains("\"amount_cents\":1050"), json)
    val decimal = json.replace("\"amount_cents\":1050", "\"amount_cents\":10.50")
    intercept[Exception](readFromString[MoneyTransactionsFact](decimal)(using MoneyTransactions.codec))
  }

  test("the merchants' key facts never carry a raw key") {
    PaymentsMerchants.families.foreach(f => assert(!f.fields.exists(_._1 == "key"), f.name))
  }
