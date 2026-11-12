package io.eitheror.casino.publicapi.wire

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.publicapi.records.*

import java.time.Instant

/**
 * Each record as the contract's shape (contracts/fields.md): ids, money as two-decimal strings, enums mapped and null
 * where a word has no mapping (never coerced), optional fields omitted, never null. The identifying fields of a player
 * are written by `Fields`, only those the key allows.
 */
object Records:
  private val mapper = ObjectMapper()

  private final class Obj(brand: String):
    val node: ObjectNode = mapper.createObjectNode()
    def str(name: String, v: String): Obj               = { node.put(name, v); this }
    def opt(name: String, v: Option[String]): Obj       = { v.foreach(node.put(name, _)); this }
    def nullable(name: String, v: Option[String]): Obj  = { v.fold(node.putNull(name))(node.put(name, _)); this }
    def time(name: String, v: Instant): Obj             = str(name, Times.of(v))
    def optTime(name: String, v: Option[Instant]): Obj  = opt(name, v.map(Times.of))
    def money(name: String, minor: Long, ccy: String): Obj = str(name, Money.of(minor, ccy).getOrElse(Money.cents(minor)))
    def brandId: Obj                                    = str("brandId", brand)
    def json: String                                    = mapper.writeValueAsString(node)

  /** The player's JSON and the identifying fields it carries. */
  def player(s: PlayerState, brand: String, allowed: Set[String]): (String, Vector[String]) =
    val o = Obj(brand).str("id", s.id).brandId.nullable("status", Enums.playerStatus(s.status)).nullable("kycStatus", Enums.kycStatus(s.kycStatus))
      .time("registeredAt", s.registeredAt.getOrElse(Instant.EPOCH)).opt("country", s.country).opt("currency", s.currency).opt("language", s.language)
    val served = Fields.write(o.node, s, allowed)
    (o.json, served)

  def session(s: SessionState, brand: String): String =
    Obj(brand).str("id", s.id).brandId.str("playerId", s.playerId).opt("gameId", s.gameId).time("startedAt", s.startedAt.getOrElse(Instant.EPOCH))
      .optTime("endedAt", s.endedAt).str("status", if s.endedAt.isDefined then "CLOSED" else "OPEN").opt("closeReason", s.closeReason).json

  def round(s: RoundState, brand: String): String =
    Obj(brand).str("id", s.id).brandId.str("playerId", s.playerId).opt("sessionId", s.sessionId).str("gameId", s.gameId)
      .time("startedAt", s.startedAt.getOrElse(Instant.EPOCH)).optTime("settledAt", s.settledAt).money("stakeTotal", s.stakeCents, s.currency)
      .money("winTotal", s.winCents, s.currency).str("currency", s.currency).opt("status", Option(s.status).filter(Set("open", "settled", "cancelled"))).json

  def bet(s: BetState, brand: String): String =
    Obj(brand).str("id", s.id).brandId.str("playerId", s.playerId).opt("roundId", s.roundId).opt("sessionId", s.sessionId)
      .money("amount", s.amountCents, s.currency).str("currency", s.currency).nullable("fundedFrom", Enums.walletType(s.fundedFrom))
      .opt("status", Option(s.status).filter(_.nonEmpty)).time("placedAt", s.placedAt.getOrElse(Instant.EPOCH)).json

  def transaction(s: TransactionState, brand: String): String =
    Obj(brand).str("id", s.id).brandId.str("playerId", s.playerId).nullable("type", Enums.transactionType(s.typeWord))
      .money("amount", s.amountCents, s.currency).str("currency", s.currency).opt("walletType", s.walletType)
      .opt("status", Option(s.status).filter(_.nonEmpty)).time("createdAt", s.createdAt.getOrElse(Instant.EPOCH)).opt("roundId", s.roundId)
      .opt("gameId", s.gameId).opt("paymentRef", s.paymentRef).json

  def bonusAward(s: BonusAwardState, brand: String): String =
    val ccy = s.currency.getOrElse("")
    val o = Obj(brand).str("id", s.id).brandId.str("playerId", s.playerId).str("offerId", s.offerId.getOrElse("")).opt("name", s.name)
      .opt("type", s.offerType).money("value", s.valueCents.getOrElse(0L), ccy).str("currency", ccy)
    s.wageringRequirementCents.foreach(o.money("wageringRequirement", _, ccy))
    s.wageredCents.foreach(o.money("wageredAmount", _, ccy))
    o.nullable("status", s.status.flatMap(Enums.bonusStatus)).time("grantedAt", s.grantedAt.getOrElse(Instant.EPOCH))
      .optTime("expiresAt", s.expiresAt).json
