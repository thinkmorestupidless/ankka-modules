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

  for topic <- Topics.all.filterNot(_.flat); family <- topic.families do
    test(s"${topic.name} ${family.name}: the codec writes the family as `type` and exactly the schema's fields, and reads it back") {
      val t = topic.asInstanceOf[Topic[Fact]]
      val json = encoded(t, family)
      assertEquals(json.get("type").asText(), family.name)
      val written = json.fieldNames().asScala.toSet - "type"
      assertEquals(written, family.fields.map(_._1).toSet)
      val back = readFromString[Fact](mapper.writeValueAsString(json))(using t.codec)
      assertEquals(back, family.sample)
    }

  test("every topic the modules declare has its facts, and every brand topic is some module's") {
    assertEquals(Topics.brand.map(_.name).toSet, Layout.BrandTopics.toSet)
    assertEquals(Layout.BrandTopics.distinct, Layout.BrandTopics, "a topic is in one module only")
  }

  test("platform.modules is the one flat topic: no body `type`, exactly the vendor's fields, keyed by the module (spec 021 E3)") {
    assertEquals(Topics.all.filter(_.flat).map(_.name), Vector("platform.modules"))
    val fact = ModuleChange("casino", "enabled", Some("1.4.0"), Some("stable"), "token:abc", java.time.Instant.parse("2026-10-22T10:00:00Z"))
    val json = mapper.readTree(PlatformModules.encode(fact))
    assertEquals(json.fieldNames().asScala.toSet, Set("module", "change", "version", "channel", "actor", "at"))
    assertEquals(json.get("at").asText(), "2026-10-22T10:00:00Z")
    assertEquals(readFromString[ModuleChange](mapper.writeValueAsString(json))(using PlatformModules.codec), fact)
    assertEquals(fact.key, "casino")
    val requested = mapper.readTree(PlatformModules.encode(fact.copy(change = "requested", version = None, channel = None)))
    assert(!requested.has("version") && !requested.has("channel"), "absent for a request")
    val schema = mapper.readTree(PlatformModules.schema.render())
    assert(!schema.path("properties").has("type"), "the schema has no body type")
    assertEquals(schema.path("required").elements().asScala.map(_.asText()).toSet, Set("module", "change", "actor", "at"))
    assertEquals(schema.path("properties").path("change").path("enum").elements().asScala.map(_.asText()).toVector, ModuleChange.Changes)
  }

  test("every topic's contract is the one the brands tool declares from contract/schemas") {
    val root = java.nio.file.Paths.get(sys.props.getOrElse("casino.root", "."))
    Topics.all.foreach { topic =>
      val file     = java.nio.file.Files.readString(root.resolve(s"contract/schemas/${topic.contractName}.json")).trim
      val declared = com.thinkmorestupidless.ankka.core.Contract.fromSchema(topic.contractName, file.getBytes("UTF-8"))
      assertEquals(Right(topic.contract), declared, topic.name)
    }
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
      else if topic.name != "platform.audit" && topic.name != "platform.modules" then assert(has, s"${topic.name} ${family.name}")
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

  test("spec 008's additions: player_reopened has the status families' fields, and game_session_ended an optional close reason") {
    def family(topic: Topic[?], name: String) = topic.families.find(_.name == name).getOrElse(fail(s"$name is missing"))
    assertEquals(family(CasinoPlayers, "player_reopened").fields.map(_._1).toSet, family(CasinoPlayers, "player_closed").fields.map(_._1).toSet)
    val reason = family(CasinoSessions, "game_session_ended").fields.toMap.get("close_reason")
    assert(reason.exists(_.optional), s"close_reason must be optional, so the first shape's readers still read it: $reason")
  }

  test("round_settled carries the provider's and the wallet's ids and the grant, keyed by the wallet (spec 003 FR-019)") {
    val family = CasinoRounds.families.find(_.name == "round_settled").get
    val fields = family.fields.toMap
    assert(Set("provider_transaction_ids", "wallet_transaction_ids", "free_rounds_grant").subsetOf(fields.keySet))
    assert(fields("free_rounds_grant").optional, "the grant is present only on a tagged round")
    assert(!fields("wallet_transaction_ids").optional)
    val fact = RoundSettled(
      playerId = "p-1", currency = io.eitheror.casino.contract.money.Currency.unsafe("EUR"), roundId = "st8/r-1",
      provider = "st8", providerRoundId = "r-1", gameId = "g", sessionId = "s",
      stakeCents = io.eitheror.casino.contract.money.MinorUnits.unsafe(200),
      winCents = io.eitheror.casino.contract.money.MinorUnits.unsafe(500),
      netResultCents = io.eitheror.casino.contract.money.SignedMinorUnits(300), outcome = RoundSettled.Outcome.Win,
      transactionIds = Vector("w-1", "w-2"), providerTransactionIds = Vector("t-1", "t-2"),
      walletTransactionIds = Vector("w-1", "w-2"), freeRoundsGrant = None, occurredAt = java.time.Instant.EPOCH
    )
    assertEquals(fact.key, "p-1/EUR")
    val json = CasinoRounds.encode(fact)
    assert(json.contains("\"provider_transaction_ids\":[\"t-1\",\"t-2\"]"), json)
    assert(!json.contains("free_rounds_grant"), json)
    assertEquals(RoundSettled.Outcome.all, Set("win", "loss", "cancelled", "credit"))
  }

  test("every money family carries the balances after the movement") {
    MoneyTransactions.families.foreach { f =>
      assert(Set("real_balance_after", "restricted_balance_after").subsetOf(f.fields.map(_._1).toSet), f.name)
    }
  }

  test("spec 002's additions: the reversal and the provider credit are families, the clawback and the void are on the fact") {
    def fields(family: String) = MoneyTransactions.families.find(_.name == family).map(_.fields.map(_._1).toSet).getOrElse(Set.empty)
    assert(Set("withdrawal_request_id", "amount_cents").subsetOf(fields("withdrawal_reversed")))
    assert(Set("provider_ref", "amount_cents", "landed_on", "award_id").subsetOf(fields("provider_credited")))
    assert(fields("restricted_funds_forfeited").contains("clawback_cents"))
    assert(fields("hold_placed").contains("voided_cents"))
    assert(MoneyTransactions.families.find(_.name == "provider_credited").get.fields.exists((n, t) => n == "award_id" && t.optional))
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
