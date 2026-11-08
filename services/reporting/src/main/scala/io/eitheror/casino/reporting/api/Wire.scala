package io.eitheror.casino.reporting.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.Settings
import io.eitheror.casino.reporting.intake.Coverage
import io.eitheror.casino.reporting.ranges.Ranges
import io.eitheror.casino.reporting.ranges.Ranges.Period

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate

/**
 * What every route has in common (contracts/routes.md, *Common to every route*): answers as JSON with every field
 * written, nulls included, as the original's; refusals as `{"error": <code>, …}`; the period in reporting days of the
 * brand's zone, the month to date by default; the currency among those the brand holds figures in (FR-003); and a
 * period before the earliest complete day refused `409 incomplete_period` (FR-004).
 */
object Wire:
  type Answer = Respond[Bytes]

  /** An answer's codec: every field written, so a `null` rate is `null` under its name (FR-011, FR-027). */
  inline def answer[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](CodecMakerConfig.withTransientEmpty(false).withTransientNone(false).withTransientDefault(false)
      .withMapMaxInsertNumber(Int.MaxValue))

  def json[A](status: Int, a: A)(using c: JsonValueCodec[A]): Answer =
    Respond(Bytes("application/json", writeToString(a).getBytes(UTF_8)), status)

  def ok[A: JsonValueCodec](a: A): Answer = json(200, a)

  /** A document served as an attachment under a file name carrying its reference (FR-021). */
  def attachment(contentType: String, fileName: String, body: Array[Byte]): Answer =
    Respond(Bytes(contentType, body), 200, Vector("Content-Disposition" -> s"attachment; filename=\"$fileName\""))

  // ── refusals ──────────────────────────────────────────────────────────────────────────────────

  /** A refusal's body: the code, and the field, range, currencies or day it names, each only when it names one. */
  final case class Refusal(error: String, field: Option[String] = None, message: Option[String] = None,
      currencies: Option[Vector[String]] = None, completeFrom: Option[String] = None)

  private given JsonValueCodec[Refusal] =
    JsonCodecMaker.make[Refusal](CodecMakerConfig.withTransientNone(true).withTransientEmpty(false).withTransientDefault(false))

  def refuse(status: Int, r: Refusal): Answer = json(status, r)

  object Codes:
    val NotFound          = "not_found"
    val UnknownCurrency   = "unknown_currency"
    val IncompletePeriod  = "incomplete_period"
    val InvalidSetting    = "invalid_setting"
    val InvalidDate       = "invalid_date"
    val InvalidBody       = "invalid_body"

  /** A route the brand's settings hide (the original's `backoffice-reporting-tab` flag): not found. */
  val notFound: Answer = refuse(404, Refusal(Codes.NotFound))

  def invalidSetting(field: String, message: String): Answer = refuse(400, Refusal(Codes.InvalidSetting, Some(field), Some(message)))

  val invalidBody: Answer = refuse(400, Refusal(Codes.InvalidBody, message = Some("the body is not a JSON object")))

  // ── the common parameters ─────────────────────────────────────────────────────────────────────

  /** `from` and `to`, defaulting to the month to date (FR-007); a date that is not `YYYY-MM-DD` is refused naming it. */
  def period(from: Option[String], to: Option[String], today: LocalDate): Either[Answer, Period] =
    def date(field: String, v: Option[String]): Either[Answer, Option[LocalDate]] =
      v.map(_.trim).filter(_.nonEmpty) match
        case None    => Right(None)
        case Some(t) => Ranges.parse(t).map(Some(_)).left.map(_ =>
          refuse(400, Refusal(Codes.InvalidDate, Some(field), Some(s"$field is not a date as YYYY-MM-DD, not $t"))))
    val d = Ranges.defaultPeriod(today)
    for
      f <- date("from", from)
      t <- date("to", to)
    yield Period(f.getOrElse(d.from), t.getOrElse(d.to))

  /**
   * The currency a report answers in (FR-003): the one asked for, refused naming the held ones when the brand holds no
   * figures in it; with none asked, `default`. The brand's set default counts as held, so a brand with no figures yet
   * answers zeros in it.
   */
  def currency(asked: Option[String], held: Vector[String], configured: Option[String], default: => String): Either[Answer, String] =
    val known = (held ++ configured).distinct.sorted
    asked.map(_.trim.toUpperCase).filter(_.nonEmpty) match
      case Some(c) if known.contains(c) => Right(c)
      case Some(_)                      => Left(refuse(400, Refusal(Codes.UnknownCurrency, currencies = Some(held))))
      case None                         => Right(default)

  /** A period starting before the first complete day is refused naming that day (FR-004). */
  def complete(period: Period, completeFrom: Option[LocalDate]): Either[Answer, Unit] =
    completeFrom.filter(period.from.isBefore) match
      case Some(day) => Left(refuse(409, Refusal(Codes.IncompletePeriod, completeFrom = Some(day.toString))))
      case None      => Right(())

  /** The answer of the first refusal, or of `body`. */
  def answered(e: Either[Answer, Answer]): Answer = e.merge

/**
 * How far back a view's figures are complete (research R8): each topic's `Coverage` gives the first whole reporting
 * day after its earliest fact in the generation, and a view is complete from the latest of its topics'. A topic with
 * nothing folded yet holds nothing back.
 */
object Completeness:
  /** The topics `Days` folds (research R3): the wallet, payments, withdrawals, rewards when the casino runs, players. */
  def dayTopics(settings: Settings): Vector[String] =
    Vector(f.MoneyTransactions.name, f.PaymentsPayments.name, f.CasinoWithdrawals.name, f.CasinoPlayers.name) ++
      Option.when(settings.casino)(f.CasinoRewards.name)

  /** Each view `/status` lists, with the topics its rows are folded from, in the order the service registers them. */
  def viewTopics(settings: Settings): Vector[(String, Vector[String])] =
    val money   = f.MoneyTransactions.name
    val players = f.CasinoPlayers.name
    val rewards = Option.when(settings.casino)(f.CasinoRewards.name).toVector
    val rounds  = Option.when(settings.casino)(f.CasinoRounds.name).toVector
    Vector(
      "days"         -> dayTopics(settings),
      "wallets"      -> Vector(money),
      "players"      -> Vector(players),
      "sessions"     -> Vector(f.CasinoSessions.name),
      "payments"     -> Vector(f.PaymentsPayments.name),
      "withdrawals"  -> Vector(f.CasinoWithdrawals.name),
      "rounds"       -> (rounds :+ money),
      "player-days"  -> Vector(money),
      "activity"     -> (players +: rewards),
      "transactions" -> Vector(money),
      "grants"       -> rewards,
      "compliance"   -> Vector(players)
    )

  def from(components: ComponentClient, settings: Settings, topics: Vector[String]): Option[LocalDate] =
    topics.flatMap { topic =>
      components.forKeyValueEntity(EntityId(Coverage.id(settings.generation, topic))).call(Coverage.get).invoke().earliest
        .map(Coverage.completeFrom(_, settings.reportingZone))
    }.maxOption
