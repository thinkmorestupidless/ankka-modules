package io.eitheror.casino.audit.query

import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import scala.util.Try

/**
 * What a query of the store asks for (contracts/routes.md, `GET /v1/audit-records`). `brands` is the
 * caller's scope narrowed by the `brand` asked for: `None` is every brand. `correlations` are the
 * digests of the raw correlation id asked for, one per brand in scope (research R5).
 */
final case class Filters(
    brands: Option[Set[String]] = None,
    subject: Option[String] = None,
    actor: Option[String] = None,
    service: Option[String] = None,
    action: Option[String] = None,
    outcome: Option[String] = None,
    reasonCode: Option[String] = None,
    category: Option[String] = None,
    visibility: Option[String] = None,
    amountMin: Option[Long] = None,
    amountMax: Option[Long] = None,
    currency: Option[String] = None,
    correlations: Vector[String] = Vector.empty,
    from: Option[Instant] = None,
    to: Option[Instant] = None
)

/** The last row of a page: the keyset `(occurredAt, key)` the next page starts after. */
final case class Cursor(occurredAt: Instant, key: String):
  def encode: String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(s"${occurredAt.toString}|$key".getBytes(UTF_8))

object Cursor:
  def decode(text: String): Option[Cursor] =
    Try(String(Base64.getUrlDecoder.decode(text), UTF_8)).toOption.flatMap { s =>
      s.split("\\|", 2) match
        case Array(at, key) if key.nonEmpty => Try(Instant.parse(at)).toOption.map(Cursor(_, key))
        case _                              => None
    }

/** A request as the route reads it: the filters before the scope and the correlation's digests are applied. */
final case class Asked(brand: Option[String], filters: Filters, correlation: Option[String], limit: Int, after: Option[Cursor])

object Query:
  val BadFilter  = "bad_filter"
  val BadCursor  = "bad_cursor"
  val DefaultLimit = 100

  private val Text = Vector("subject", "actor", "service", "action", "outcome", "reasonCode", "category", "currency")

  /** The query string as asked, refused naming the parameter; `limit` at most `pageLimit`. */
  def parse(params: Map[String, String], pageLimit: Int): Either[String, Asked] =
    def opt(name: String) = params.get(name).map(_.trim).filter(_.nonEmpty)
    def long(name: String) = opt(name).fold(Right(None))(v => v.toLongOption.map(Some(_)).toRight(s"$BadFilter: $name '$v' is not a whole number of minor units"))
    def instant(name: String) = opt(name).fold(Right(None))(v => Try(Instant.parse(v)).toOption.map(Some(_)).toRight(s"$BadFilter: $name '$v' is not an instant"))
    for
      _     <- params.keys.find(k => !Known(k)).map(k => s"$BadFilter: '$k' is not a filter").toLeft(())
      min   <- long("amountMin")
      max   <- long("amountMax")
      _     <- Either.cond(min.zip(max).forall(_ <= _), (), s"$BadFilter: amountMin is above amountMax")
      from  <- instant("from")
      to    <- instant("to")
      _     <- Either.cond(from.zip(to).forall((f, t) => f.isBefore(t)), (), s"$BadFilter: from is not before to")
      vis   <- opt("visibility") match
                 case None | Some("brand" | "installation") => Right(opt("visibility"))
                 case Some(v)                               => Left(s"$BadFilter: visibility '$v' is brand or installation")
      limit <- opt("limit").fold(Right(DefaultLimit))(v =>
                 v.toIntOption.filter(n => n > 0 && n <= pageLimit).toRight(s"$BadFilter: limit '$v' is from 1 to $pageLimit"))
      after <- opt("after").fold(Right(None))(v => Cursor.decode(v).map(Some(_)).toRight(s"$BadCursor: the cursor is not one this service gave"))
    yield
      val f = Filters(subject = opt("subject"), actor = opt("actor"), service = opt("service"), action = opt("action"),
        outcome = opt("outcome"), reasonCode = opt("reasonCode"), category = opt("category"), visibility = vis,
        amountMin = min, amountMax = max, currency = opt("currency"), from = from, to = to)
      Asked(opt("brand"), f, opt("correlation"), limit, after)

  private val Known: Set[String] = Set("brand", "correlation", "visibility", "amountMin", "amountMax", "from", "to", "limit", "after") ++ Text

  private def at(field: String) = SqlFragment.raw(s"(payload::jsonb->>'$field')::timestamptz")
  private def number(field: String) = SqlFragment.raw(s"(payload::jsonb->>'$field')::bigint")
  private val key = jsonText("key")

  /** Oldest first, the key breaking a tie: the order every page and stream is read in. */
  val Oldest: SqlFragment = at("occurredAt") ++ SqlFragment.raw(" ASC, payload::jsonb->>'key' ASC")

  private def in(field: String, values: Iterable[String]): SqlFragment =
    val vs = values.toVector.sorted
    if vs.isEmpty then SqlFragment.raw("1 = 0")
    else jsonText(field) ++ SqlFragment.raw(" IN (") ++ vs.map(v => sql"$v").reduce((a, b) => a ++ SqlFragment.raw(", ") ++ b) ++ SqlFragment.raw(")")

  private def and(parts: Vector[SqlFragment]): SqlFragment =
    if parts.isEmpty then sql"1 = 1" else parts.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)

  /** The condition the view is asked: every filter, and the keyset after the cursor. */
  def condition(f: Filters, after: Option[Cursor] = None): SqlFragment =
    def eq(field: String, v: Option[String]) = v.map(x => jsonText(field) ++ sql" = $x")
    and(Vector(
      f.brands.map(in("brand", _)),
      eq("subject", f.subject),
      eq("actor", f.actor),
      eq("service", f.service),
      eq("action", f.action),
      eq("outcome", f.outcome),
      eq("reasonCode", f.reasonCode),
      eq("category", f.category),
      eq("visibility", f.visibility),
      eq("currency", f.currency),
      f.amountMin.map(n => number("amount") ++ sql" >= $n"),
      f.amountMax.map(n => number("amount") ++ sql" <= $n"),
      Option.when(f.correlations.nonEmpty)(in("correlation", f.correlations)),
      f.from.map(i => at("occurredAt") ++ sql" >= ${i.toString}::timestamptz"),
      f.to.map(i => at("occurredAt") ++ sql" < ${i.toString}::timestamptz"),
      after.map(c => SqlFragment.raw("(") ++ at("occurredAt") ++ sql" > ${c.occurredAt.toString}::timestamptz OR (" ++
        at("occurredAt") ++ sql" = ${c.occurredAt.toString}::timestamptz AND " ++ key ++ sql" > ${c.key}))")
    ).flatten)
