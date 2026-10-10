package io.eitheror.casino.contract.facts

import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/**
 * What a field of a fact is in the contract's schema, and a value of it for the round-trip check.
 * Only the types facts are written in have one, so a family with any other field does not compile.
 */
trait FieldType[A]:
  def schema: Json
  def sample: A
  def optional: Boolean = false

object FieldType:
  private def of[A](s: Json, v: A): FieldType[A] = new FieldType[A]:
    def schema = s
    def sample = v

  given FieldType[String]  = of(Json.obj("type" -> Json.str("string")), "x")
  given FieldType[Int]     = of(Json.obj("type" -> Json.str("integer")), 1)
  given FieldType[Long]    = of(Json.obj("type" -> Json.str("integer")), 1L)
  given FieldType[Boolean] = of(Json.obj("type" -> Json.str("boolean")), true)
  given FieldType[Instant] = of(Json.obj("type" -> Json.str("string"), "format" -> Json.str("date-time")), Instant.parse("2026-10-09T00:00:00Z"))
  given FieldType[MinorUnits] =
    of(Json.obj("type" -> Json.str("integer"), "minimum" -> Json.Num(0), "x-unit" -> Json.str("minor")), MinorUnits.unsafe(1050))
  given FieldType[SignedMinorUnits] =
    of(Json.obj("type" -> Json.str("integer"), "x-unit" -> Json.str("minor")), SignedMinorUnits(-1050))
  given FieldType[Currency] =
    of(Json.obj("type" -> Json.str("string"), "pattern" -> Json.str("^[A-Z]{3}$")), Currency.unsafe("EUR"))

  given [A](using inner: FieldType[A]): FieldType[Option[A]] with
    def schema            = inner.schema
    def sample            = Some(inner.sample)
    override def optional = true

  given [A](using inner: FieldType[A]): FieldType[Vector[A]] with
    def schema = Json.obj("type" -> Json.str("array"), "items" -> inner.schema)
    def sample = Vector(inner.sample)

  /** A personal field carries its marker in the schema (spec 001 FR-023, research R5). */
  given [A](using inner: FieldType[A]): FieldType[Personal[A]] with
    def schema = inner.schema match
      case Json.Obj(fields) => Json.Obj(fields :+ ("x-personal" -> Json.Bool(true)))
      case other            => other
    def sample = Personal(inner.sample)
