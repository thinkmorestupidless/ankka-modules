package io.eitheror.casino.players.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.time.{Duration, Instant}
import scala.util.Try

/** `may-log-in`'s answer (spec 010 FR-009). */
enum LogIn:
  case Allowed
  case Excluded
  case CoolingOff(until: Instant)

/** `may-deposit`'s answer (spec 010 FR-007; 002 `contracts/routes.md`): a refusal keeps its body to pass on. */
enum DepositDecision:
  case Allowed
  case Refused(code: String, body: JsonNode)

/** The wallet's deposit sums in minor units, as `may-deposit` takes them. */
final case class DepositSums(last24h: Long, last7d: Long, last30d: Long)

/**
 * `responsible-gambling` within the project, under `/v1/casino/brands/<brand>/rg` as the wallet calls
 * it. Each call raced against a bound the caller names (sign-in 500 ms, the realm's authenticator 80 ms,
 * the deposit pre-check 500 ms); an unanswered or unreadable call is the caller's to fail closed.
 */
trait Rg:
  def mayLogIn(playerId: String, bound: Duration): Raced[LogIn]
  def mayDeposit(playerId: String, currency: String, amount: Long, sums: DepositSums, bound: Duration): Raced[DepositDecision]
  /**
   * The GAMSTOP authenticator's refusal, passed on by player (spec 010 FR-022, R12): `excluded`, `api_error`
   * or `timeout`. The address stays here; responsible gambling holds none.
   */
  def gamstopRefusal(playerId: String, result: String, bound: Duration): Raced[Unit]

object Rg:
  val Service = "responsible-gambling"

  def http(services: ServiceClients, brand: String): Rg = HttpRg(services, Target(Service, s"/v1/casino/brands/$brand/rg"))

  /** An instant as epoch milliseconds or ISO-8601 text. */
  def instant(node: JsonNode, field: String): Option[Instant] =
    node.long(field).map(Instant.ofEpochMilli).orElse(node.text(field).flatMap(t => Try(Instant.parse(t)).toOption))

  def logIn(r: Reply): Either[String, LogIn] =
    if !r.ok then Left(s"status ${r.status}")
    else r.body.flatMap(_.text("decision")).map(_.toLowerCase) match
      case Some("allowed")     => Right(LogIn.Allowed)
      case Some("excluded")    => Right(LogIn.Excluded)
      case Some("cooling_off") => r.body.flatMap(instant(_, "endsAt")).map(LogIn.CoolingOff(_)).toRight("cooling_off without endsAt")
      case other               => Left(s"decision ${other.getOrElse("missing")}")

  def deposit(r: Reply): Either[String, DepositDecision] =
    if !r.ok then Left(s"status ${r.status}")
    else r.body.flatMap(b => b.text("decision").map(_.toLowerCase -> b)) match
      case Some(("allowed", _)) => Right(DepositDecision.Allowed)
      case Some((code, body))   => Right(DepositDecision.Refused(code, body))
      case None                 => Left("no decision")

private final class HttpRg(services: ServiceClients, target: Target) extends Rg:
  import Neighbour.{bounded, call}

  def mayLogIn(playerId: String, bound: Duration): Raced[LogIn] =
    bounded(target.name, bound)(call(services, target, "POST", "/may-log-in", Some(Json.obj("playerId" -> playerId))))(Rg.logIn)

  def mayDeposit(playerId: String, currency: String, amount: Long, sums: DepositSums, bound: Duration): Raced[DepositDecision] =
    val body = Json.obj("playerId" -> playerId, "currency" -> currency, "amount" -> amount,
      "last24h" -> sums.last24h, "last7d" -> sums.last7d, "last30d" -> sums.last30d)
    bounded(target.name, bound)(call(services, target, "POST", "/may-deposit", Some(body)))(Rg.deposit)

  def gamstopRefusal(playerId: String, result: String, bound: Duration): Raced[Unit] =
    bounded(target.name, bound)(call(services, target, "POST", "/gamstop-refusal", Some(Json.obj("playerId" -> playerId, "result" -> result))))(r =>
      if r.ok then Right(()) else Left(s"status ${r.status}"))
