package io.eitheror.casino.simulator.st8

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.simulator.core.{Call, Protocol, Reading, RoundRole}
import io.eitheror.casino.simulator.st8.GoJson.Value
import io.eitheror.casino.simulator.st8.GoJson.Value.*

import scala.util.Try

/**
 * ST8's protocol as the original's adapter reads it and its stand-in and test harness send it
 * (contracts/st8-wire.md; spec 003 FR-002 to FR-006). Two body shapes: `stub`, the stand-in's
 * string-only alphabetical maps, and `harness`, the Go end-to-end suite's fuller bodies (research R7).
 */
object St8Protocol extends Protocol:

  /** The status words the adapter answers (FR-012). */
  object Words:
    val Ok                  = "ok"
    val Error               = "error"
    val SessionExpired      = "session_expired"
    val PlayerLocked        = "player_locked"
    val PlayerNotFound      = "player_not_found"
    val NotEnoughMoney      = "not_enough_money"
    val SpendingLimit       = "spending_limit"
    val TransactionNotFound = "transaction_not_found"
    val ProviderUnavailable = "provider_unavailable"
    val AuthFailed          = "auth_failed"
    val all = Set(Ok, Error, SessionExpired, PlayerLocked, PlayerNotFound, NotEnoughMoney, SpendingLimit, TransactionNotFound, ProviderUnavailable, AuthFailed)

  val Kinds = Vector("check", "balance", "player_profile", "debit", "credit", "cancel", "buyin", "payout")

  private val mapper = ObjectMapper()

  def defaultRetryOn: Set[String] = Set(Words.Error)

  def route(kind: String): String =
    require(Kinds.contains(kind), s"'$kind' is not an ST8 callback")
    s"/st8/$kind"

  def defaultProviderKind(kind: String): Option[String] = kind match
    case "debit"  => Some("debit")
    case "credit" => Some("credit")
    case "buyin"  => Some("correction_debit")
    case "payout" => Some("free_credit")
    case _        => None

  def roleOf(kind: String, providerKind: Option[String], amount: Option[String]): RoundRole = kind match
    case "debit"                                                    => RoundRole.Stake
    case "credit" if amount.exists(a => Try(BigDecimal(a)).toOption.contains(BigDecimal(0))) => RoundRole.Loss
    case "credit"                                                   => RoundRole.Win
    case "cancel"                                                   => RoundRole.Cancel
    case _                                                          => RoundRole.Outside

  def accepted(reading: Reading): Boolean = reading.status.contains(Words.Ok)

  /** A loss is a credit of "0.00" on the round, as the stand-in sends it (FR-013). */
  def loss(token: Option[String], round: String, currency: String, gameCode: String, transactionId: String): Call =
    Call("credit", token, Some(transactionId), Some(round), Some(currency), Some("0.00"), Some("credit"), Some(gameCode), None, None, None)

  def win(token: Option[String], round: String, currency: String, gameCode: String, transactionId: String, amount: String): Call =
    Call("credit", token, Some(transactionId), Some(round), Some(currency), Some(amount), Some("credit"), Some(gameCode), None, None, None)

  def stake(token: Option[String], round: String, currency: String, gameCode: String, transactionId: String, amount: String): Call =
    Call("debit", token, Some(transactionId), Some(round), Some(currency), Some(amount), Some("debit"), Some(gameCode), None, None, None)

  def check(launchToken: String): Call = Call("check", Some(launchToken), None, None, None, None, None, None, None, None, None)

  def balanceKind: String = "balance"

  /** The bytes of a callback (FR-009). A malformed body is not a JSON object (the scenario "malformed body"). */
  def body(shape: String, call: Call): Array[Byte] =
    if call.malformed then s"{\"token\": ${call.token.map(t => "\"" + t + "\"").getOrElse("null")}, broken".getBytes("UTF-8")
    else
      val text = shape match
        case "stub"    => GoJson.marshal(stub(call))
        case "harness" => GoJson.marshal(harness(call))
        case other     => throw IllegalArgumentException(s"no body shape '$other': stub or harness")
      text.getBytes("UTF-8")

  /** The stand-in's bodies (`main.go:767, 865-873, 420-428, 914-922, 962-970, 1011-1017`): strings only, keys sorted. */
  private def stub(call: Call): Value =
    def s(v: Option[String]) = v.getOrElse("")
    call.kind match
      case "check" => GoJson.strings("token" -> s(call.token))
      case "debit" | "credit" =>
        GoJson.strings(
          "amount" -> s(call.amount), "currency" -> s(call.currency), "game_code" -> s(call.gameCode),
          "provider_kind" -> s(call.providerKind.orElse(defaultProviderKind(call.kind))), "round" -> s(call.round),
          "token" -> s(call.token), "transaction_id" -> s(call.transactionId)
        )
      case "cancel" =>
        GoJson.strings("cancel_id" -> s(call.cancelId), "currency" -> s(call.currency), "round" -> s(call.round), "token" -> s(call.token), "transaction_id" -> s(call.transactionId))
      case other => throw IllegalArgumentException(s"the stand-in never sends '$other'; use the harness shape")

  /** The harness's bodies (`scenario15_st8_wallet_e2e_test.go:259-268, 280-293, 319-335, 350-365, 372-412`), in its field order. */
  private def harness(call: Call): Value =
    def opt(v: Option[String]): Value = v.map(Str(_)).getOrElse(Null)
    def provider: Value = GoJson.obj(
      "amount" -> Str("12.345"), "currency" -> Str("USD"), "metadata" -> Null, "player" -> Str("external"),
      "round" -> opt(call.round), "transaction_id" -> opt(call.transactionId)
    )
    val kind = call.providerKind.orElse(defaultProviderKind(call.kind))
    val fields: Vector[(String, Value)] = call.kind match
      case "check"          => Vector("token" -> opt(call.token))
      case "balance"        => Vector("player" -> opt(call.player)) ++ call.token.map(t => "token" -> Str(t)) ++ Vector("currency" -> opt(call.currency), "site" -> opt(call.site))
      case "player_profile" => Vector("player" -> opt(call.player), "site" -> opt(call.site))
      case "debit" | "credit" =>
        Vector(
          "token" -> opt(call.token), "transaction_id" -> opt(call.transactionId), "round" -> opt(call.round),
          "round_closed" -> Bool(call.kind == "credit"), "currency" -> opt(call.currency), "amount" -> opt(call.amount),
          "provider_kind" -> opt(kind), "player" -> opt(call.player), "site" -> opt(call.site),
          "developer_code" -> Str("some"), "game_code" -> opt(call.gameCode), "bonus" -> Null, "provider" -> provider
        )
      case "cancel" =>
        call.token.map(t => "token" -> Str(t)).toVector ++ Vector(
          "cancel_id" -> opt(call.cancelId), "transaction_id" -> opt(call.transactionId)
        ) ++ call.round.map(r => "round" -> Str(r)) ++ Vector("currency" -> opt(call.currency)) ++
          call.amount.map(a => "amount" -> Str(a)) ++ Vector("player" -> opt(call.player)) ++
          call.site.map(s => "site" -> Str(s)) ++ call.gameCode.map(g => "game_code" -> Str(g))
      case "buyin" =>
        Vector(
          "player" -> opt(call.player), "transaction_id" -> opt(call.transactionId), "currency" -> opt(call.currency),
          "amount" -> opt(call.amount), "provider_kind" -> opt(kind), "round" -> Null, "round_closed" -> Bool(false),
          "site" -> opt(call.site), "developer_code" -> Str("some"), "bonus" -> Null, "provider" -> provider
        )
      case "payout" =>
        Vector(
          "player" -> opt(call.player), "transaction_id" -> opt(call.transactionId), "currency" -> opt(call.currency),
          "amount" -> opt(call.amount), "provider_kind" -> opt(kind), "round" -> Null, "round_closed" -> Bool(false),
          "site" -> opt(call.site), "developer_code" -> Str("some"),
          "bonus" -> GoJson.obj("bonus_id" -> Str("bonus_1234"), "instance_id" -> Str("player_1234_bonus_1234"), "status" -> Str("finished")),
          "provider" -> provider
        )
      case other => throw IllegalArgumentException(s"'$other' is not an ST8 callback")
    Obj(fields ++ call.extra.toVector.map((k, v) => k -> GoJson.fromJackson(v)))

  /** HTTP 200 with a status word; HTTP 400 a body the brand could not parse; anything else is retried (FR-012). */
  def read(httpStatus: Int, body: Array[Byte]): Reading =
    val json = Try(mapper.readTree(body)).toOption.collect { case o: ObjectNode => o }
    def field(name: String): Option[String] =
      json.flatMap(j => Option(j.get(name))).filter(!_.isNull).map(n => if n.isNumber then n.decimalValue().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString else n.asText())
    val status = field("status")
    httpStatus match
      case 400 => Reading(status, None, None, None, malformedBody = true, retryable = false)
      case 200 if status.isDefined =>
        Reading(status, field("balance"), field("currency"), field("token"), malformedBody = false, retryable = status.exists(defaultRetryOn.contains))
      case _ => Reading(status, None, None, None, malformedBody = false, retryable = true)

  /** `player_profile`'s personal fields are replaced before the answer is recorded (FR-023). */
  def redact(kind: String, body: Array[Byte]): Array[Byte] =
    if kind != "player_profile" then body
    else
      Try(mapper.readTree(body)).toOption.collect { case o: ObjectNode => o } match
        case None => body
        case Some(o) =>
          Vector("birth_date", "reg_country", "reg_date").foreach(f => if o.has(f) then o.put(f, "<redacted>"): Unit)
          mapper.writeValueAsBytes(o)

  /** A JSON object's text field, for adapters reading the stand-in's request bodies. */
  def text(node: JsonNode, field: String): Option[String] =
    Option(node.get(field)).filter(!_.isNull).map(n => if n.isTextual then n.asText() else n.toString)
