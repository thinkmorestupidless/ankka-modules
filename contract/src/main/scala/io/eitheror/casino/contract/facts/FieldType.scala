package io.eitheror.casino.contract.facts

import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant
import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

/**
 * What a field of a fact is in the contract's schema, and a value of it for the round-trip check.
 * Only the types facts are written in have one, so a family with any other field does not compile.
 */
trait FieldType[A]:
  def schema: Json
  def sample: A
  def optional: Boolean = false

  /** A nested object's fields, by their snake_case names; empty for a scalar (spec 023). */
  def nested: Vector[(String, FieldType[?])] = Vector.empty

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
    override def nested   = inner.nested

  given [A](using inner: FieldType[A]): FieldType[Vector[A]] with
    def schema          = Json.obj("type" -> Json.str("array"), "items" -> inner.schema)
    def sample          = Vector(inner.sample)
    override def nested = inner.nested

  /**
   * A nested object: a case class whose fields are themselves field types, written as an object with
   * its own `required` and `properties` (spec 023: a trade's legs, a price per outcome). Declared by
   * the case class's companion, `given FieldType[Leg] = FieldType.nested[Leg]`, never derived for
   * every product, so a family's shape is always chosen.
   */
  inline def nested[A](using m: Mirror.ProductOf[A]): FieldType[A] =
    val labels = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    val types  = summonAll[Tuple.Map[m.MirroredElemTypes, FieldType]].toList.map(_.asInstanceOf[FieldType[?]])
    val fields = labels.map(FamilyDef.snake).zip(types).toVector
    val value  = m.fromProduct(Tuple.fromArray(types.map(_.sample.asInstanceOf[Object]).toArray))
    new FieldType[A]:
      def schema =
        Json.obj(
          "type"                 -> Json.str("object"),
          "additionalProperties" -> Json.Bool(true),
          "required"             -> Json.Arr(fields.filterNot(_._2.optional).map(f => Json.str(f._1))),
          "properties"           -> Json.Obj(fields.map((n, t) => n -> t.schema))
        )
      def sample          = value
      override def nested = fields

  /** A personal field carries its marker in the schema (spec 001 FR-023, research R5). */
  given [A](using inner: FieldType[A]): FieldType[Personal[A]] with
    def schema = inner.schema match
      case Json.Obj(fields) => Json.Obj(fields :+ ("x-personal" -> Json.Bool(true)))
      case other            => other
    def sample          = Personal(inner.sample)
    override def nested = inner.nested
