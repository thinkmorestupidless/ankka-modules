package io.eitheror.casino.players.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.time.Duration

/** The deposit check's answer (spec 009 FR-019): allowed, or the code and message to pass on. */
enum KycDecision:
  case Allowed
  case Refused(code: String, message: String)

/** `kyc` within the project, under `/v1/casino/brands/<brand>/kyc`; the currency is the deposit's (009 R5). */
trait Kyc:
  def depositCheck(playerId: String, amount: Long, currency: String, bound: Duration): Raced[KycDecision]

object Kyc:
  val Service = "kyc"

  def http(services: ServiceClients, brand: String): Kyc = new Kyc:
    import Neighbour.{bounded, call}
    private val target = Target(Service, s"/v1/casino/brands/$brand/kyc")
    def depositCheck(playerId: String, amount: Long, currency: String, bound: Duration): Raced[KycDecision] =
      bounded(Service, bound)(call(services, target, "POST", "/deposit-check", Some(Json.obj("playerId" -> playerId, "amount" -> amount, "currency" -> currency))))(read)

  def read(r: Reply): Either[String, KycDecision] =
    if !r.ok then Left(s"status ${r.status}")
    else r.body match
      case Some(b) if b.bool("allowed").contains(true) => Right(KycDecision.Allowed)
      case Some(b) => b.text("code").map(c => KycDecision.Refused(c, b.textOr("message", ""))).toRight("refused without a code")
      case None    => Left("no body")

/** What `engagement`'s send route answered (spec 014 US4). */
enum SendOutcome:
  case Accepted
  case NotConfigured
  case Suppressed(reason: String)

/**
 * `engagement` within the project, under `/v1/casino/brands/<brand>/engagement`: one transactional send
 * under an idempotency key, naming the trigger (`EmailAddressVerification`, `EmailChange`,
 * `PasswordChange`), the player, the address and the link. A repeat of the key sends nothing.
 */
trait Engagement:
  def send(key: String, playerId: String, trigger: String, address: String, link: String, bound: Duration): Raced[SendOutcome]

object Engagement:
  val Service = "engagement"

  def http(services: ServiceClients, brand: String): Engagement = new Engagement:
    import Neighbour.{bounded, call}
    private val target = Target(Service, s"/v1/casino/brands/$brand/engagement")
    def send(key: String, playerId: String, trigger: String, address: String, link: String, bound: Duration): Raced[SendOutcome] =
      val body = Json.obj("key" -> key, "playerId" -> playerId, "trigger" -> trigger, "to" -> address, "link" -> link)
      bounded(Service, bound)(call(services, target, "POST", "/sends", Some(body)))(read)

  def read(r: Reply): Either[String, SendOutcome] =
    if !r.ok then Left(s"status ${r.status}")
    else r.body.flatMap(_.text("status")).map(_.toLowerCase) match
      case Some("accepted")       => Right(SendOutcome.Accepted)
      case Some("not_configured") => Right(SendOutcome.NotConfigured)
      case Some("suppressed")     => Right(SendOutcome.Suppressed(r.body.flatMap(_.text("reason")).getOrElse("")))
      case other                  => Left(s"status ${other.getOrElse("missing")}")

/** The brand's `wallet`, under `/v1/casino/brands/<brand>`: the deposit sums `may-deposit` takes (002 `deposit-sums`). */
trait Wallet:
  def depositSums(playerId: String, currency: String, bound: Duration): Raced[DepositSums]

object Wallet:
  val Service = "wallet"

  def http(services: ServiceClients, brand: String): Wallet = new Wallet:
    import Neighbour.{bounded, call, segment}
    private val target = Target(Service, s"/v1/casino/brands/$brand")
    def depositSums(playerId: String, currency: String, bound: Duration): Raced[DepositSums] =
      bounded(Service, bound)(call(services, target, "GET", s"/wallets/${segment(playerId)}/${segment(currency)}/deposit-sums"))(read)

  def read(r: Reply): Either[String, DepositSums] =
    if !r.ok then Left(s"status ${r.status}")
    else
      (for
        b   <- r.body
        d1  <- b.long("last24h")
        d7  <- b.long("last7d")
        d30 <- b.long("last30d")
      yield DepositSums(d1, d7, d30)).toRight("sums missing")

/**
 * Every brand service's own erasure and export routes (contracts/routes.md, R10): `POST
 * /erasure/players/{id}` answering its certificate, `GET /exports/players/{id}` answering its part.
 * The workflows hold each target's answer as it came; a non-2xx is not one.
 */
trait Erasure:
  def apply(target: Target, playerId: String, bound: Duration): Raced[JsonNode]

trait Export:
  def part(target: Target, playerId: String, bound: Duration): Raced[JsonNode]

object Erasure:
  def http(services: ServiceClients): Erasure = new Erasure:
    import Neighbour.{bounded, call, segment}
    def apply(target: Target, playerId: String, bound: Duration): Raced[JsonNode] =
      bounded(target.describe, bound)(call(services, target, "POST", s"/erasure/players/${segment(playerId)}"))(answered)

  def answered(r: Reply): Either[String, JsonNode] =
    if r.ok then r.body.toRight("no body") else Left(s"status ${r.status}")

object Export:
  def http(services: ServiceClients): Export = new Export:
    import Neighbour.{bounded, call, segment}
    def part(target: Target, playerId: String, bound: Duration): Raced[JsonNode] =
      bounded(target.describe, bound)(call(services, target, "GET", s"/exports/players/${segment(playerId)}"))(Erasure.answered)
