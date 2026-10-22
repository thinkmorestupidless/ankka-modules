package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.core.Serializer

import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

/**
 * One statement that something happened (glossary: fact). On the wire it is a CloudEvent whose
 * `ce-type` is the topic's contract name (`<topic>.v1`, which ankka sets and checks) and whose body
 * carries the family in its `type` field (spec 001 FR-011, FR-013).
 */
trait Fact:
  /** The id of the entity the fact is about: the record key, so its facts stay in order (FR-012). */
  def key: String

/** A family of a topic: its name, its fields' schema, and a value to prove the codec agrees. */
final case class FamilyDef[A](name: String, fields: Vector[(String, FieldType[?])], sample: A):
  def schema: Json =
    val required = fields.filterNot(_._2.optional).map(_._1)
    Json.obj(
      "title"                -> Json.str(name),
      "type"                 -> Json.str("object"),
      "additionalProperties" -> Json.Bool(true),
      "required"             -> Json.Arr((Vector("type") ++ required).map(Json.str)),
      "properties"           -> Json.Obj(("type" -> Json.obj("const" -> Json.str(name))) +: fields.map((n, t) => n -> t.schema))
    )

object FamilyDef:
  /** jsoniter's `enforce_snake_case`, so the schema names the fields exactly as the codec writes them. */
  def snake(name: String): String =
    val b = StringBuilder()
    name.foreach { c =>
      if c.isUpper then
        if b.nonEmpty then b += '_'
        b += c.toLower
      else b += c
    }
    b.result()

  inline def of[A](name: String)(using m: Mirror.ProductOf[A]): FamilyDef[A] =
    val labels = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    val types  = summonAll[Tuple.Map[m.MirroredElemTypes, FieldType]].toList.map(_.asInstanceOf[FieldType[?]])
    val sample = m.fromProduct(Tuple.fromArray(types.map(_.sample.asInstanceOf[Object]).toArray))
    FamilyDef(name, labels.map(snake).zip(types).toVector, sample)

/**
 * A topic of the contract: its name, producer and families, the codec of its sealed family type,
 * and the ankka serializer services publish and read it with (manifest = the contract name).
 */
abstract class Topic[F <: Fact](val name: String, val producers: Vector[String])(using val codec: JsonValueCodec[F]):
  def families: Vector[FamilyDef[? <: F]]

  def contractName: String = s"$name.v1"

  /** A topic whose body is one flat record with no `type` (only `platform.modules`, spec 021 E3). */
  def flat: Boolean = false

  lazy val serializer: Serializer[F] = Serializer.json[F](contractName)

  /**
   * The topic's ankka contract: its name and the fingerprint of the schema `contract/schemas` holds, which
   * is what the brands tool declares on the project. A service names it on the view or consumer that reads
   * or publishes the topic, or ankka refuses to start it (T082).
   */
  lazy val contract: com.thinkmorestupidless.ankka.core.Contract =
    com.thinkmorestupidless.ankka.core.Contract
      .fromSchema(contractName, schema.render().getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .fold(e => throw IllegalStateException(s"$contractName: $e"), identity)

  def encode(fact: F): String = writeToString(fact)

  def schema: Json =
    Json.obj(
      "$schema"     -> Json.str("https://json-schema.org/draft/2020-12/schema"),
      "$id"         -> Json.str(s"https://eitheror.io/schemas/casino/$contractName.json"),
      "title"       -> Json.str(contractName),
      "description" -> Json.str(s"The facts on $name, produced by ${producers.mkString(", ")} (spec 001 FR-011). The family is the body's `type`; `ce-type` is the contract name."),
      "oneOf"       -> Json.Arr(families.map(_.schema))
    )
