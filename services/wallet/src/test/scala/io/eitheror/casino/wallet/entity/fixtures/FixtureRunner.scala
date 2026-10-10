package io.eitheror.casino.wallet.entity.fixtures

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.funding.{Clawback, Leg, Record, Resolver, Status}

import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * The original's TL handler fixtures (`starter-packs/wallet/handlers/<handler>.test.tl`, converted once by
 * `tools/tl-fixtures` into `src/test/resources/fixtures/<handler>.json`), replayed against the
 * wallet entity (research R13).
 *
 * A case's `given` twins become one `open-migrated` command, the starting state the migration would
 * write for the same wallet (spec 019); its `when` becomes the matching command; its `then` is
 * checked on the balance-bearing values only: the result's balances, the wallet's and the records'
 * `set_properties`, and the new transaction's balances, legs and wallet type. Twin, edge and concept
 * mutations are the substrate's and are not asserted. Expected values are the fixture's.
 *
 * Fixture numbers are minor units (a decimal `10.00` is 10 minor units, its integer part); only the
 * decimal strings (`amount_str`, a row's `"amount": "30.00"`) are major units, and none is asserted.
 */
object FixtureRunner:

  final case class Mutation(index: Int, op: String, target: String, properties: JsonNode)

  final case class Case(handler: String, name: String, line: Int, twins: Vector[JsonNode], command: String, params: JsonNode,
      mutations: Vector[Mutation], result: Option[JsonNode], error: Option[JsonNode]):
    def wallet: JsonNode = twins.find(t => text(t, "id").exists(_.startsWith("wallet/"))).getOrElse(Json.emptyObject)

    def recordTwins: Vector[JsonNode] =
      twins.filter(t => text(t, "id").exists(_.startsWith("restricted-funds/")) || concepts(t).contains("RestrictedFunds"))

    /** The `set_properties` mutations on a target, in order. */
    def setOn(target: String): Vector[JsonNode] = mutations.filter(m => m.op == "set_properties" && m.target == target).map(_.properties)

    /** The new transactions the case asserts, of one type. */
    def transactions(kind: String): Vector[JsonNode] =
      mutations.filter(m => m.op == "set_properties" && m.target.startsWith("transaction.new-id") && text(m.properties, "type").contains(kind))
        .map(_.properties)

    def at: Instant =
      Seq("created_at", "resolved_at", "reversed_at").flatMap(text(params, _)).headOption.map(Instant.parse).getOrElse(DefaultAt)

  val DefaultAt: Instant = Instant.parse("2026-09-22T10:00:00Z")

  private val mapper = ObjectMapper()

  private object Json:
    val emptyObject: JsonNode = mapper.createObjectNode()

  def load(handler: String): Vector[Case] =
    val stream = Option(getClass.getResourceAsStream(s"/fixtures/$handler.json"))
      .getOrElse(throw IllegalStateException(s"no fixture file fixtures/$handler.json on the test classpath"))
    val root = try mapper.readTree(stream) finally stream.close()
    root.get("cases").elements().asScala.toVector.map { c =>
      val expected = c.get("then")
      Case(
        handler = handler,
        name = c.get("name").asText(),
        line = c.path("line").asInt(0),
        twins = c.get("given").elements().asScala.toVector,
        command = c.get("when").get("command").asText(),
        params = c.get("when").get("params"),
        mutations = expected.get("mutations").elements().asScala.toVector.map(m =>
          Mutation(m.get("index").asInt(), m.get("op").asText(), m.get("target").asText(), m.path("properties"))),
        result = Option(expected.get("result")).filterNot(_.isNull),
        error = Option(expected.get("error")).filterNot(_.isNull)
      )
    }

  // ── reading values ────────────────────────────────────────────────────────

  def text(node: JsonNode, field: String): Option[String] =
    Option(node.get(field)).filterNot(_.isNull).map(_.asText())

  /** A fixture number as minor units: its integer part. */
  def long(node: JsonNode, field: String): Option[Long] =
    Option(node.get(field)).filterNot(_.isNull).map(n => if n.isNumber then n.decimalValue().longValue() else n.asText().toLong)

  def concepts(node: JsonNode): Set[String] =
    Option(node.get("concepts")).map(_.elements().asScala.map(_.asText()).toSet).getOrElse(Set.empty)

  /** The original's pipe set: `|provider:evil|` (parse.go ParsePipeSet). */
  def pipeSet(s: String): Set[String] = s.split('|').iterator.map(_.trim).filter(_.nonEmpty).toSet

  /** The original's rate table: `category:table:10|` (parse.go ParseRateTable): the rate after the last colon. */
  def rateTable(s: String): Map[String, Long] =
    s.split('|').iterator.map(_.trim).filter(_.nonEmpty).flatMap { entry =>
      val i = entry.lastIndexOf(':')
      if i <= 0 then None else entry.substring(i + 1).toLongOption.map(entry.substring(0, i) -> _)
    }.toMap

  def legs(s: String): Vector[Leg] = Resolver.parseLegList(s).fold(e => throw IllegalArgumentException(e), identity)

  // ── the starting state ────────────────────────────────────────────────────

  /** A record twin as the migration carries it, with every rule field the original's text formats hold. */
  def record(t: JsonNode): AwardedRecord =
    val id        = text(t, "id").get
    val grantedAt = text(t, "granted_at").getOrElse("2026-06-01T00:00:00Z")
    val r = Record(
      id = id,
      grantRef = text(t, "grant_ref").getOrElse(""),
      status = text(t, "status").getOrElse(Status.Active),
      priority = long(t, "priority").getOrElse(0L),
      grantedAt = grantedAt,
      balance = long(t, "balance").getOrElse(0L),
      releaseRequirement = long(t, "release_requirement").getOrElse(0L),
      releasedProgress = long(t, "released_progress").getOrElse(0L),
      maxConvertible = long(t, "max_convertible_amount").getOrElse(0L),
      remainingAllowance = long(t, "remaining_allowance").getOrElse(0L),
      depositsSinceRelease = long(t, "deposits_since_release").getOrElse(0L),
      openRoundCount = long(t, "open_round_count").getOrElse(0L),
      maxStake = long(t, "max_stake"),
      maxStakeEnforcement = text(t, "max_stake_enforcement").getOrElse(""),
      contributionDefault = long(t, "contribution_default"),
      contributionOverrides = text(t, "contribution_overrides").map(rateTable).getOrElse(Map.empty),
      scopeInclude = text(t, "scope_include").map(pipeSet).getOrElse(Set.empty),
      scopeExclude = text(t, "scope_exclude").map(pipeSet).getOrElse(Set.empty),
      scopeViolationMode = text(t, "scope_violation_mode").getOrElse(""),
      fundingOrder = text(t, "funding_order").getOrElse(""),
      winAllocation = text(t, "win_allocation").getOrElse(""),
      releaseProgressScope = text(t, "release_progress_scope").getOrElse(""),
      forfeitOnWithdrawal = text(t, "forfeit_on_withdrawal").getOrElse("")
    )
    AwardedRecord(r, bonusRef = "", expiresAt = None, awardedAt = Instant.parse(grantedAt), baseline = None)

  /**
   * The open bet a settlement fixture names, from the legs its parameters carry: the record legs
   * from `funded_breakdown`, the real leg from `funded_real_amount`, else the bet amount less the
   * record legs (the original's subtraction), else, for a bet with no legs at all and no amount
   * (a credit-win fixture's legacy bet), a one-unit real leg, which funds the bet from real alone
   * as the original treated it. Contributions from `contribution_breakdown`, else the legacy
   * `wagering_contribution` on the primary active record, as the original synthesised it.
   */
  def openBet(c: Case, records: Vector[AwardedRecord]): Bet =
    val p          = c.params
    val recordLegs = text(p, "funded_breakdown").map(legs).getOrElse(Vector.empty)
    val legTotal   = recordLegs.map(_.amount).sum
    val realLeg = long(p, "funded_real_amount")
      .orElse(long(p, "bet_amount").map(_ - legTotal))
      .getOrElse(if recordLegs.isEmpty then 1L else 0L)
    val contributions = text(p, "contribution_breakdown").filter(_.nonEmpty).map(legs).getOrElse {
      val legacy  = long(p, "wagering_contribution").getOrElse(0L)
      val primary = Resolver.ordered(records.map(_.record).filter(r => Status.isOpen(r.status))).find(_.status == Status.Active)
      primary.filter(_ => legacy > 0).map(r => Vector(Leg(r.id, legacy))).getOrElse(Vector.empty)
    }
    val betKey = text(p, "bet_tx_id").getOrElse("bet")
    Bet(betKey, text(p, "round_id").get, s"tx-$betKey", realLeg + legTotal, realLeg, recordLegs, contributions,
      Resolver.fundingWalletType(realLeg, recordLegs), BetStatus.Open, "s", "g", DefaultAt.minusSeconds(60))

  /** The `open-migrated` command that puts a fixture's wallet in its starting state. */
  def opening(c: Case, records: Vector[AwardedRecord], openBets: Vector[Bet] = Vector.empty,
      withdrawals: Vector[WithdrawalRequest] = Vector.empty): OpenMigrated =
    val w          = c.wallet
    val restricted = long(w, "restricted_balance").orElse(long(w, "bonus_balance")).getOrElse(0L)
    val anyOpen    = records.exists(a => Status.isOpen(a.status))
    OpenMigrated(
      key = "fixture-opening",
      realBalance = long(w, "real_balance").getOrElse(0L),
      depositTurnoverPending = long(w, "deposit_turnover_pending").getOrElse(0L),
      records = records,
      totalDeposits = 0L,
      depositOrdinal = 0,
      restrictedWithoutRecords = if anyOpen then 0L else restricted,
      at = DefaultAt.minusSeconds(3600),
      openBets = openBets,
      withdrawals = withdrawals,
      pendingRequestId = text(w, "pending_withdrawal_request_ref")
    )

  /** A baseline that makes the wallet's own formula (FR-015a) yield `clawback` with no deposits since award. */
  def baselineFor(realNow: Long, clawback: Long): Clawback.Baseline = Clawback.Baseline(realNow - clawback, 0L)

  /** A wallet test kit opened at the fixture's starting state. */
  def kit(c: Case, opening: OpenMigrated): EventSourcedTestKit[WalletEntity, WalletState, WalletEvent] =
    val kit    = EventSourcedTestKit.of(WalletEntity, "fixture-player/GBP")
    val opened = kit.call(WalletEntity.openMigrated)(opening)
    assert(opened.replyValue.accepted, s"the starting state of '${c.name}' was refused: ${opened.replyValue}")
    kit
