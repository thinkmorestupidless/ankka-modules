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

  test("spec 022: the seven wallet families are keyed by the wallet and carry the reserved balance after; account_adjusted is keyed by the account") {
    val wallet = Vector("reservation_placed", "reservation_released", "settlement_debit", "settlement_credit",
      "resolution_payment", "account_debit", "account_credit")
    wallet.foreach { name =>
      val family = MoneyTransactions.families.find(_.name == name).getOrElse(fail(s"no family $name"))
      assertEquals(family.sample.asInstanceOf[Fact].key, "x/EUR", name)
      assert(family.fields.exists(_._1 == "reserved_balance_after"), name)
    }
    val adjusted = AccountAdjusted("clearing", "north", io.eitheror.casino.contract.money.Currency.unsafe("EUR"), "t",
      io.eitheror.casino.contract.money.SignedMinorUnits(500), "correction", "op-1",
      io.eitheror.casino.contract.money.SignedMinorUnits(0), io.eitheror.casino.contract.money.MinorUnits.unsafe(500),
      java.time.Instant.parse("2026-10-25T10:00:00Z"))
    assertEquals(adjusted.key, "clearing/north/EUR")
    val back = readFromString[MoneyTransactionsMessage](MoneyTransactions.encode(adjusted))(using MoneyTransactions.codec)
    assertEquals(back, adjusted)
  }

  test("a fact about a player carries player_id, except attribution, which never does, and a non-player account's (FR-012; spec 022)") {
    for topic <- Topics.brand; family <- topic.families do
      val has = family.fields.exists(_._1 == "player_id")
      if topic.name == "affiliates.attribution" then assert(!has, family.name)
      else if family.sample.isInstanceOf[AccountFact] then assert(!has, s"${family.name} is about an account, not a player")
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

  test("spec 009's addition: player_registered carries the registration's profile as player_profile_updated does, optional") {
    val fields  = CasinoPlayers.families.find(_.name == "player_registered").get.fields.toMap
    val profile = Seq("given_name", "family_name", "date_of_birth", "telephone", "address_line", "city", "post_code")
    assert(profile.forall(fields.get(_).exists(_.optional)), s"every profile field present and optional: ${fields.keySet}")
    val updated = CasinoPlayers.families.find(_.name == "player_profile_updated").get.fields.toMap
    assertEquals(profile.map(fields(_).schema), profile.map(updated(_).schema))
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

  test("every money family carries the balances after the movement: a wallet's real and restricted, an account's its own (spec 022 FR-011)") {
    MoneyTransactions.families.foreach { f =>
      val after = if f.sample.isInstanceOf[AccountFact] then Set("balance_after") else Set("real_balance_after", "restricted_balance_after")
      assert(after.subsetOf(f.fields.map(_._1).toSet), f.name)
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

  private val withParts = Set("deposit_confirmed", "bet_placed", "win_credited", "bet_rolled_back", "restricted_funds_converted",
    "restricted_funds_forfeited", "admin_adjusted", "hold_placed", "hold_released", "withdrawal_reversed", "provider_credited",
    // spec 022's movements of real balance (its research R16)
    "settlement_debit", "settlement_credit", "resolution_payment", "account_debit", "account_credit")

  /** Keyed by a non-player account, not a wallet: it has no pools (spec 022 contracts/facts.md). */
  private val accountKeyed = Set("account_adjusted")

  test("spec 025: every money family carries both pools after it, and each that moves real balance its parts, all optional") {
    MoneyTransactions.families.filterNot(f => accountKeyed(f.name)).foreach { f =>
      val fields = f.fields.toMap
      val expected = Vector("deposit_pool_after", "converted_pool_after") ++
        (if withParts(f.name) then Vector("deposit_part_cents", "converted_part_cents") else Vector.empty) ++
        (if f.name == "hold_placed" then Vector("voided_deposit_cents", "voided_converted_cents") else Vector.empty)
      expected.foreach(n => assert(fields.get(n).exists(_.optional), s"${f.name}.$n is missing or required"))
      if !withParts(f.name) then assert(!fields.contains("deposit_part_cents"), s"${f.name} moves no real balance")
    }
  }

  test("spec 025: adding the pools keeps money.transactions at v1 (spec 001 FR-013)") {
    val before = MoneyTransactions.families.map { f =>
      f.copy(fields = f.fields.filterNot((n, _) => n.contains("pool_after") || n.contains("_part_cents") || n.startsWith("voided_deposit") || n.startsWith("voided_converted")))
    }
    assertEquals(Compatibility.compare(before, MoneyTransactions.families), Compatibility.Verdict.SameVersion)
  }

  test("spec 025: a fact written before the pools still decodes, its pools and parts absent") {
    val bet = """{"type":"bet_placed","player_id":"p1","currency":"EUR","transaction_id":"t1","round_id":"r1","game_id":"g1","amount_cents":1500,"wallet_type":"real","real_balance_after":500,"restricted_balance_after":0,"occurred_at":"2026-10-28T00:00:00Z"}"""
    val hold = """{"type":"hold_placed","player_id":"p1","currency":"EUR","transaction_id":"t2","withdrawal_request_id":"w1","amount_cents":2000,"voided_cents":500,"real_balance_after":0,"restricted_balance_after":0,"occurred_at":"2026-10-28T00:00:00Z"}"""
    readFromString[MoneyTransactionsMessage](bet)(using MoneyTransactions.codec) match
      case b: BetPlaced =>
        assertEquals((b.depositPoolAfter, b.convertedPoolAfter, b.depositPartCents, b.convertedPartCents), (None, None, None, None))
      case other => fail(s"not a bet: $other")
    readFromString[MoneyTransactionsMessage](hold)(using MoneyTransactions.codec) match
      case h: HoldPlaced => assertEquals((h.voidedDepositCents, h.voidedConvertedCents, h.depositPartCents), (None, None, None))
      case other         => fail(s"not a hold: $other")
  }

  test("an amount is written as a whole number, never a decimal") {
    val json = writeToString(MoneyTransactions.families.head.sample.asInstanceOf[MoneyTransactionsFact])(using MoneyTransactions.codec)
    assert(json.contains("\"amount_cents\":1050"), json)
    val decimal = json.replace("\"amount_cents\":1050", "\"amount_cents\":10.50")
    intercept[Exception](readFromString[MoneyTransactionsMessage](decimal)(using MoneyTransactions.codec))
  }

  test("the merchants' key facts never carry a raw key") {
    PaymentsMerchants.families.foreach(f => assert(!f.fields.exists(_._1 == "key"), f.name))
  }

  test("ggr-daily carries before_binding when a day closed before the binding, and reads as absent otherwise (spec 012 FR-016)") {
    import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
    val base = GgrDaily(1, "ggr-daily/p/EUR/2026-11-01", java.time.Instant.parse("2026-11-02T00:15:00Z"), "goldvibe", "programme",
      "aff-1", "lnk-1", None, Vector.empty, None, "p", Currency.unsafe("EUR"), "2026-11-01",
      MinorUnits.unsafe(600), MinorUnits.unsafe(400), SignedMinorUnits(200), "EUR/2026-11-01")
    val absent = mapper.readTree(AffiliatesAttribution.encode(base))
    assert(!absent.has("before_binding"), absent.toString)
    assertEquals(readFromString[AttributionFact](absent.toString)(using AffiliatesAttribution.codec), base)
    val marked = base.copy(beforeBinding = Some(true))
    val json   = mapper.readTree(AffiliatesAttribution.encode(marked))
    assertEquals(json.get("before_binding").asBoolean(), true)
    assertEquals(readFromString[AttributionFact](json.toString)(using AffiliatesAttribution.codec), marked)
    assert(AffiliatesAttribution.families.find(_.name == "ggr-daily").get.fields.exists((n, t) => n == "before_binding" && t.optional))
  }
