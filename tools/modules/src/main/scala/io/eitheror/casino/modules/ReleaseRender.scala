package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.modules.ModuleManifest.{ContractView, Refusal}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * What the release publishes (spec 021 FR-004, contracts/release.md): every manifest of the tree with
 * its images at the tag's version and each topic's contract schema inlined from the contract
 * artifact, so the agent declares the topic with its contract as the brands tool did. Every rendered
 * manifest is validated again before it is written; nothing is published here.
 */
object ReleaseRender:

  private val mapper = ObjectMapper()
  private val Version = "[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?".r

  /** `v1.4.0` → `1.4.0`; anything else is not a release version. */
  def version(tag: String): Either[String, String] =
    val v = tag.stripPrefix("v")
    if Version.matches(v) then Right(v) else Left(s"'$tag' is not a release version (v<major>.<minor>.<patch>)")

  /** One manifest at a version: images retagged, topics given their contract. */
  def render(m: ModuleManifest, version: String): Either[Vector[Refusal], ObjectNode] =
    val doc = m.json.deepCopy()
    val services = doc.path("services").elements().asScala.toVector
    val retag = services.zipWithIndex.flatMap { (s, i) =>
      val d     = s.get("descriptor").asInstanceOf[ObjectNode]
      val image = d.path("image").asText()
      if image.endsWith(":0.0.0") then
        d.put("image", image.stripSuffix(":0.0.0") + s":$version"); None
      else Some(Refusal(s"services[$i].descriptor.image", s"'$image' is not at 0.0.0 in the tree, so the release cannot set its version"))
    }
    val schemas = doc.path("topics").elements().asScala.toVector.zipWithIndex.flatMap { (t, i) =>
      val name = t.path("name").asText()
      Render.schema(name) match
        case Some(text) =>
          val contract = mapper.createObjectNode()
          contract.put("name", s"$name.v1")
          contract.set("schema", mapper.readTree(text))
          t.asInstanceOf[ObjectNode].set("contract", contract)
          None
        case None => Some(Refusal(s"topics[$i].name", s"no schema for $name.v1 in the contract artifact"))
    }
    if retag.nonEmpty || schemas.nonEmpty then Left(retag ++ schemas) else Right(doc)

  /**
   * Every manifest of the tree at `version` into `out/<module>.json`, each validated after rendering.
   * Writes nothing unless every one renders and validates.
   */
  def all(root: Path, version: String, out: Path): Either[Vector[(String, Refusal)], Vector[Path]] =
    val view = ContractView.fromTree(root)
    val (valid, refused) = ModuleManifest.validateTree(root, view)
    if refused.nonEmpty then Left(refused.flatMap((p, rs) => rs.map(root.relativize(p).toString -> _)))
    else
      val rendered = valid.map(m => m.name -> render(m, version).flatMap(doc => ModuleManifest.parse(mapper.writeValueAsString(doc)).flatMap { again =>
        val problems = ModuleManifest.rules(again, view)
        if problems.isEmpty then Right(doc) else Left(problems)
      }))
      val problems = rendered.collect { case (n, Left(rs)) => rs.map(s"modules/$n/module.json" -> _) }.flatten
      if problems.nonEmpty then Left(problems)
      else
        Files.createDirectories(out)
        Right(rendered.collect { case (n, Right(doc)) =>
          val file = out.resolve(s"$n.json")
          Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n")
          file
        })
