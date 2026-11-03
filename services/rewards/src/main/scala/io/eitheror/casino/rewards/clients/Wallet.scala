package io.eitheror.casino.rewards.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.{Json, Settings}
import io.eitheror.casino.rewards.Json.*

import java.time.Instant

/**
 * The offer's rules as the wallet's award takes them, flattened (spec 002 `AwardRequest`); an empty text or set
 * is left out and the wallet applies its own default.
 */
final case class AwardRules(
    maxStake: Option[Long] = None,
    maxStakeEnforcement: String = "",
    contributionDefault: Option[Long] = None,
    contributionOverrides: Map[String, Long] = Map.empty,
    scopeInclude: Set[String] = Set.empty,
    scopeExclude: Set[String] = Set.empty,
    scopeViolationMode: String = "",
    fundingOrder: String = "",
    winAllocation: String = "",
    releaseProgressScope: String = "",
    forfeitOnWithdrawal: String = ""
)

/**
 * An award (research R5's `award` and `winnings` steps): `bonusRef` the offer id (`manual:<uuid>` with no offer),
 * `maxWin` the maximum convertible, `maxConcurrentGrants` the offer's limit (W1; none is the brand's).
 */
final case class AwardTerms(
    bonusRef: String,
    amount: Long,
    wageringMultiplier: Long,
    maxWin: Long,
    priority: Option[Long],
    expiresAt: Option[Instant],
    rules: AwardRules = AwardRules(),
    maxConcurrentGrants: Option[Int] = None
)

/** The balances a money command leaves (the wallet's `MoneyReply`). */
final case class Balances(realBalance: Long, bonusBalance: Long)

final case class Awarded(recordId: Option[String], wageringRequirement: Long, txnId: Option[String], balances: Balances)
final case class Forfeited(forfeited: Vector[String], balances: Balances)
final case class Cancelled(clawbackAmount: Long, forfeited: Vector[String], balances: Balances)
final case class CapClosed(closedRecordId: Option[String])
final case class Switched(priority: Option[Long], alreadyPrimary: Boolean)
/** `landedOn`: the record the credit landed on, or `real` (research R8). */
final case class Credited(txnId: Option[String], landedOn: Option[String], balances: Balances)

/** One open record as the wallet's restricted-funds read lists it. */
final case class OpenRecord(recordId: String, grantRef: String, bonusRef: String, status: String, priority: Long, balance: Long,
    wageringRequirement: Long, releaseProgress: Long, maxConvertible: Long)

final case class RestrictedFunds(records: Vector[OpenRecord], restrictedBalance: Long)

/** The balance read: `primary` is the primary record's grant, when one is open. */
final case class WalletBalance(realBalance: Long, restrictedBalance: Long, primary: Option[String])

/**
 * The brand's wallet: the six award routes, each keyed as research R5's table says (the caller names the key), and
 * the two reads (contracts/routes.md, *Calls out*). A 5xx or no answer is `Unreachable`, which the step retries.
 */
trait Wallet:
  def award(player: String, currency: Currency, key: String, terms: AwardTerms): Called[Awarded]
  def forfeit(player: String, currency: Currency, key: String, grantRef: String): Called[Forfeited]
  def cancel(player: String, currency: Currency, key: String, grantRef: String, requireBaseline: Boolean): Called[Cancelled]
  def closePendingCap(player: String, currency: Currency, key: String, grantRef: String): Called[CapClosed]
  def switch(player: String, currency: Currency, key: String, grantRef: String): Called[Switched]
  def providerCredit(player: String, currency: Currency, key: String, amount: Long, providerRef: String, reason: String,
      actor: String): Called[Credited]
  def restrictedFunds(player: String, currency: Currency): Called[RestrictedFunds]
  def balance(player: String, currency: Currency): Called[WalletBalance]

object Wallet:
  def http(services: ServiceClients, settings: Settings): Wallet = Http(Neighbour(services, settings.wallet), settings.brand)

  /** The refusals a timer's step reads as "the record is not open" (FR-015). */
  val NoActiveBonus = "NO_ACTIVE_BONUS"
  val NotPendingCap = "NOT_PENDING_CAP"
  /** A reclaim's cancel of a record with no baseline (W4). */
  val MissingBaseline = "MISSING_BASELINE"

  private def balances(b: JsonNode) = Balances(b.long("realBalance").getOrElse(0L), b.long("bonusBalance").getOrElse(0L))

  def record(n: JsonNode): Option[OpenRecord] =
    for id <- n.text("recordId") yield OpenRecord(id, n.textOr("grantRef", ""), n.textOr("bonusRef", ""), n.textOr("status", ""),
      n.long("priority").getOrElse(0L), n.long("balance").getOrElse(0L), n.long("wageringRequirement").getOrElse(0L),
      n.long("releaseProgress").getOrElse(0L), n.long("maxConvertible").getOrElse(0L))

  final class Http(wallet: Neighbour, brand: String) extends Wallet:
    private def base(player: String, currency: Currency) =
      s"/v1/casino/brands/${Neighbour.segment(brand)}/wallets/${Neighbour.segment(player)}/${currency.code}"

    private def post[A](path: String, body: JsonNode)(read: JsonNode => A): Called[A] =
      Called.of(wallet.post(path, body), "wallet")(b => Some(read(b)))

    def award(player: String, currency: Currency, key: String, t: AwardTerms): Called[Awarded] =
      val r = t.rules
      val body = Json.obj("key" -> key, "bonusRef" -> t.bonusRef, "amount" -> t.amount, "wageringMultiplier" -> t.wageringMultiplier,
        "maxWin" -> t.maxWin, "priority" -> t.priority, "expiresAt" -> t.expiresAt.map(_.toString), "maxStake" -> r.maxStake,
        "contributionDefault" -> r.contributionDefault, "maxConcurrentGrants" -> t.maxConcurrentGrants)
      Seq("maxStakeEnforcement" -> r.maxStakeEnforcement, "scopeViolationMode" -> r.scopeViolationMode, "fundingOrder" -> r.fundingOrder,
        "winAllocation" -> r.winAllocation, "releaseProgressScope" -> r.releaseProgressScope, "forfeitOnWithdrawal" -> r.forfeitOnWithdrawal)
        .filter(_._2.nonEmpty).foreach((k, v) => body.put(k, v))
      if r.contributionOverrides.nonEmpty then
        Json.set(body, "contributionOverrides", Json.obj(r.contributionOverrides.toSeq.sortBy(_._1)*))
      if r.scopeInclude.nonEmpty then Json.set(body, "scopeInclude", r.scopeInclude.toVector.sorted)
      if r.scopeExclude.nonEmpty then Json.set(body, "scopeExclude", r.scopeExclude.toVector.sorted)
      post(s"${base(player, currency)}/awards", body) { b =>
        Awarded(b.text("recordId"), b.long("wageringRequirement").getOrElse(0L), b.text("txnId"), balances(b))
      }

    def forfeit(player: String, currency: Currency, key: String, grantRef: String): Called[Forfeited] =
      post(s"${base(player, currency)}/awards/forfeit", Json.obj("key" -> key, "grantRef" -> grantRef)) { b =>
        Forfeited(b.strings("forfeited"), balances(b))
      }

    def cancel(player: String, currency: Currency, key: String, grantRef: String, requireBaseline: Boolean): Called[Cancelled] =
      val body = Json.obj("key" -> key, "grantRef" -> grantRef, "requireBaseline" -> Option.when(requireBaseline)(true))
      post(s"${base(player, currency)}/awards/cancel", body) { b =>
        Cancelled(b.long("clawbackAmount").getOrElse(0L), b.strings("forfeited"), balances(b))
      }

    def closePendingCap(player: String, currency: Currency, key: String, grantRef: String): Called[CapClosed] =
      post(s"${base(player, currency)}/awards/close-pending-cap", Json.obj("key" -> key, "grantRef" -> grantRef)) { b =>
        CapClosed(b.text("closedRecordId"))
      }

    def switch(player: String, currency: Currency, key: String, grantRef: String): Called[Switched] =
      post(s"${base(player, currency)}/awards/switch", Json.obj("key" -> key, "grantRef" -> grantRef)) { b =>
        Switched(b.long("priority"), b.bool("alreadyPrimary").getOrElse(false))
      }

    def providerCredit(player: String, currency: Currency, key: String, amount: Long, providerRef: String, reason: String,
        actor: String): Called[Credited] =
      val body = Json.obj("key" -> key, "amount" -> amount, "providerRef" -> providerRef, "reason" -> reason, "actor" -> actor)
      post(s"${base(player, currency)}/provider-credits", body)(b => Credited(b.text("txnId"), b.text("landedOn"), balances(b)))

    def restrictedFunds(player: String, currency: Currency): Called[RestrictedFunds] =
      Called.of(wallet.get(s"${base(player, currency)}/restricted-funds"), "wallet") { b =>
        Some(RestrictedFunds(b.objects("records").flatMap(record), b.long("restrictedBalance").getOrElse(0L)))
      }

    def balance(player: String, currency: Currency): Called[WalletBalance] =
      Called.of(wallet.get(s"${base(player, currency)}/balance"), "wallet") { b =>
        Some(WalletBalance(b.long("realBalance").getOrElse(0L), b.long("restrictedBalance").getOrElse(0L),
          Option(b.get("primary")).filter(_.isObject).flatMap(_.text("grantRef"))))
      }
