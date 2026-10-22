package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

import java.time.Instant

/**
 * One change to a brand's module entity (spec 021 FR-011, data-model.md): written by the vendor's
 * agent on the brand's `platform.modules` topic, keyed by the module. The one topic of the contract
 * whose body carries no `type`: the vendor's `ModuleFacts` shape is the family, a flat record, and
 * `ce-type` is the contract name as ankka sets it (E3, research R8).
 */
final case class ModuleChange(
    module: String,
    change: String,
    version: Option[String],
    channel: Option[String],
    actor: String,
    at: Instant
) extends Fact:
  def key: String = module

object ModuleChange:
  /** The changes the agent records (data-model.md). */
  val Changes: Vector[String] = Vector("enabled", "disabled", "upgraded", "failed", "requested", "values-changed")

  given JsonValueCodec[ModuleChange] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))

object PlatformModules extends Topic[ModuleChange]("platform.modules", Vector("the vendor's agent")):
  val families = Vector(FamilyDef.of[ModuleChange]("module_change"))

  override def flat: Boolean = true

  override def schema: Json =
    val family = families.head
    Json.obj(
      "$schema"              -> Json.str("https://json-schema.org/draft/2020-12/schema"),
      "$id"                  -> Json.str(s"https://eitheror.io/schemas/casino/$contractName.json"),
      "title"                -> Json.str(contractName),
      "description"          -> Json.str(s"The changes to a brand's modules on $name, produced by ${producers.mkString(", ")} (spec 021 FR-011). A flat record, the vendor's shape: no body `type`; `ce-type` is the contract name."),
      "type"                 -> Json.str("object"),
      "additionalProperties" -> Json.Bool(true),
      "required"             -> Json.Arr(family.fields.filterNot(_._2.optional).map(f => Json.str(f._1))),
      "properties"           -> Json.Obj(family.fields.map {
        case ("change", _) => "change" -> Json.obj("type" -> Json.str("string"), "enum" -> Json.Arr(ModuleChange.Changes.map(Json.str)))
        case (n, t)        => n -> t.schema
      })
    )
