package io.eitheror.casino.wallet.port

import com.fasterxml.jackson.databind.node.{JsonNodeFactory, ObjectNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.eitheror.casino.contract.port.Recording

import scala.jdk.CollectionConverters.*

/**
 * The one documented translation between the original's wallet routes and the rebuild's
 * (contracts/wire.md, research R11), applied in one place: a recorded request becomes the rebuild's,
 * and the rebuild's answer is read back in the recording's terms before `Replay` compares.
 *
 * Rules, each a divergence named in plan.md:
 *  - paths: `/v1/wallet/...` and `/internal/v1/wallet/...` become spec 001's `/v1/casino/brands/...`;
 *  - amounts: the original's decimal strings ("10.00") become minor units on the way in, and the
 *    rebuild's minor units are rendered as the recording's decimals on the way back;
 *  - a key the original minted is the step's own (`<scenario>#<step>`), so two identical requests are
 *    two commands, as they were in the original;
 *  - the answer keeps only the fields the original answered: the rebuild may say more, never less.
 *
 * Spec 025's pools (`depositPoolBalance`, `convertedPoolBalance`) and deposit parts
 * (`fundedDepositAmount`, `wonDepositAmount`) carry the original's names, so the decimal rule above
 * is their whole translation. The parts of a transaction row are not on the original's
 * transactions route (its rows carry no `funded_*`, `won_*`, `from_*` or `to_*`); a recording sees
 * them only on the twin, in `observe:` steps, which replay skips.
 */
object WireTranslation:

  private val mapper  = ObjectMapper()
  private val nodes   = JsonNodeFactory.instance
  private val Wallet  = """/(?:internal/)?v1/wallet/([^/]+)/([^/]+)/([^/]+)/(.+)""".r
  private val Decimal = """-?\d+(\.\d+)?""".r

  /** "10.00" → 1000, "10.5" → 1050, 10 (a number, already minor units) → 10. */
  def minor(n: JsonNode): Option[Long] =
    if n == null || n.isNull then None
    else if n.isIntegralNumber then Some(n.asLong())
    else
      val text = n.asText().trim
      if !Decimal.matches(text) then None
      else
        val negative     = text.startsWith("-")
        val digits       = text.stripPrefix("-")
        val (whole, fra) = digits.split('.') match
          case Array(w)    => (w, "")
          case Array(w, f) => (w, f)
          case _           => (digits, "")
        val cents = whole.toLong * 100 + (fra + "00").take(2).toLong
        Some(if negative then -cents else cents)

  /** What the step becomes on the rebuild: the method, the path and the body, plus the key it was given. */
  final case class Translated(request: Recording.Request, key: Option[String], verb: String)

  def request(scenario: String, index: Int, r: Recording.Request, brand: String): Translated =
    r.path.split('?') match
      case Array(p, rest*) =>
        p match
          case Wallet(_, player, currency, verb) =>
            val base   = s"/v1/casino/brands/$brand/wallets/$player/$currency"
            val in     = r.body.collect { case o: ObjectNode => o }.getOrElse(nodes.objectNode())
            val out    = nodes.objectNode()
            val minted = s"$scenario#$index"
            def text(names: String*): Option[String] = names.iterator.map(in.get).find(n => n != null && !n.isNull).map(_.asText())
            def amount(names: String*): Unit =
              names.iterator.map(in.get).find(n => n != null && !n.isNull).flatMap(minor).foreach(v => out.put("amount", v))
            def key(names: String*): String =
              val k = text(names*).getOrElse(minted)
              out.put("key", k)
              k
            def copy(from: String, to: String): Unit = Option(in.get(from)).foreach(v => out.set[JsonNode](to, v))
            val query = rest.headOption.fold("")(q => s"?${translateQuery(q)}")
            val (path, k) = verb match
              case "deposit" =>
                val k = text("paymentRef", "payment_ref", "paymentId").getOrElse(minted)
                out.put("paymentId", k); amount("amount")
                copy("terminalId", "terminal"); copy("pspId", "psp"); copy("scheme", "scheme"); copy("provider", "paymentProvider")
                (s"$base/deposits", Some(k))
              case "bet" =>
                val k = key("transactionId", "txnId", "txId", "key"); amount("amount")
                Seq("roundId", "gameId", "sessionId", "provider", "gameType", "allowNegative").foreach(f => copy(f, f))
                (s"$base/bets", Some(k))
              case "win" =>
                val k = key("transactionId", "txnId", "txId", "key"); amount("amount"); copy("roundId", "roundId")
                (s"$base/wins", Some(k))
              case "rollback" =>
                val k = key("transactionId", "txnId", "txId", "key"); copy("roundId", "roundId")
                (s"$base/rollbacks", Some(k))
              case "settle-loss" =>
                val k = key("transactionId", "txnId", "txId", "key"); copy("roundId", "roundId")
                (s"$base/losses", Some(k))
              case "award" =>
                val k = key("transactionId", "providerRef", "key"); amount("amount"); copy("providerRef", "providerRef"); copy("reason", "reason")
                (s"$base/provider-credits", Some(k))
              case "bonus/award" =>
                val k = key("grantRef", "grant_ref"); amount("amount")
                Seq("bonusRef", "wageringMultiplier", "priority", "maxStake").foreach(f => copy(f, f))
                Option(in.get("maxWin")).flatMap(minor).foreach(v => out.put("maxWin", v))
                (s"$base/awards", Some(k))
              case "bonus/forfeit" =>
                val k = key("key"); text("grant_ref", "grantRef").foreach(out.put("grantRef", _))
                (s"$base/awards/forfeit", Some(k))
              case "cancel-bonus" =>
                val k = key("key"); text("grantRef", "grant_ref").foreach(out.put("grantRef", _))
                (s"$base/awards/cancel", Some(k))
              case "bonus/close-pending-cap" => (s"$base/awards/close-pending-cap", Some(key("key")))
              case "bonus/switch" =>
                val k = key("key"); text("grantRef", "grant_ref").foreach(out.put("grantRef", _))
                (s"$base/awards/switch", Some(k))
              case "bonus/status" | "bonus" => (s"$base/restricted-funds", None)
              case "create-withdrawal-request" =>
                val k = key("request_id", "requestId"); amount("amount"); out.put("playerConfirmed", true)
                (s"$base/holds", Some(k))
              case v @ ("cancel-withdrawal-request" | "reject-withdrawal-request" | "fail-withdrawal-request") =>
                val k = key("key"); text("request_id", "requestId").foreach(out.put("requestId", _))
                out.put("outcome", v.takeWhile(_ != '-') match { case "cancel" => "cancelled"; case "reject" => "rejected"; case _ => "failed" })
                (s"$base/holds/release", Some(k))
              case "complete-withdrawal-request" =>
                val k = key("key"); text("request_id", "requestId").foreach(out.put("requestId", _))
                (s"$base/holds/confirm", Some(k))
              case "reverse-withdrawal-request" =>
                val k = key("key"); text("request_id", "requestId").foreach(out.put("requestId", _))
                (s"$base/holds/reverse", Some(k))
              case "admin-adjust" =>
                val k = key("key")
                val signed = Option(in.get("amount")).flatMap(minor).map(a => if text("type").contains("debit") then -math.abs(a) else math.abs(a))
                signed.foreach(out.put("amount", _))
                text("wallet_type").foreach(out.put("walletType", _)); copy("reason", "reason"); copy("actor", "actor")
                (s"$base/adjustments", Some(k))
              case "balance"          => (s"$base/balance", None)
              case "transactions"     => (s"$base/transactions$query", None)
              case "lifetime-stats"   => (s"$base/lifetime-totals", None)
              case "transaction-sum"  => (s"$base/sums$query", None)
              case other              => (s"$base/$other", None)
            Translated(Recording.Request(r.method, path, Map.empty, Option.when(r.body.isDefined)(out)), k, verb)
          case _ => Translated(r, None, "")
      case _ => Translated(r, None, "")

  private def translateQuery(q: String): String =
    q.split('&').map { kv =>
      kv.split("=", 2) match
        case Array("walletType", v) => s"walletType=$v"
        case Array("pageSize", v)   => s"pageSize=$v"
        case Array(k, v)            => s"$k=$v"
        case Array(k)               => k
    }.mkString("&")

  /** The rebuild's answers' names for the original's, where they differ. */
  private val Renamed: Map[String, String] = Map(
    "totalDeposited" -> "deposited", "totalWagered" -> "wagered", "totalWon" -> "won",
    "requestId"      -> "requestId", "voidedAmount" -> "voidedAmount", "clawbackAmount" -> "clawbackAmount"
  )

  /**
   * The rebuild's answer in the recording's terms: only the fields the original answered, each
   * rendered as the original wrote it (a decimal string where it wrote one). A hold's request id is
   * the key the step was given.
   */
  def answer(t: Translated, expected: Recording.Response, observed: Recording.Response): Recording.Response =
    val body = (expected.body, observed.body) match
      case (Some(e: ObjectNode), Some(o: ObjectNode)) =>
        val shaped = nodes.objectNode()
        e.fieldNames().asScala.foreach { name =>
          val source = Option(o.get(Renamed.getOrElse(name, name))).orElse {
            if name == "requestId" && t.verb == "create-withdrawal-request" then t.key.map(nodes.textNode) else None
          }
          source.foreach(v => shaped.set[JsonNode](name, like(e.get(name), v)))
        }
        Some(shaped)
      case (_, o) => o
    observed.copy(body = body)

  /** `value` written as `example` is: minor units as the decimal string the original wrote, else as is. */
  private def like(example: JsonNode, value: JsonNode): JsonNode =
    if example != null && example.isTextual && Decimal.matches(example.asText()) && example.asText().contains('.') && value.isIntegralNumber then
      val v    = value.asLong()
      val sign = if v < 0 then "-" else ""
      nodes.textNode(f"$sign${math.abs(v) / 100}%d.${math.abs(v) % 100}%02d")
    else value

  def parse(json: String): JsonNode = mapper.readTree(json)
