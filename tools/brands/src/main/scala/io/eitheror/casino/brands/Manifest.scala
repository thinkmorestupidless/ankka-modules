package io.eitheror.casino.brands

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.controlplane.api.ProjectId
import io.eitheror.casino.contract.Layout
import io.eitheror.casino.contract.Layout.{Grant, GrantTarget, Retention}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** One reason a manifest is refused, named by the rule it breaks (FR-010). */
final case class Problem(rule: String, message: String):
  override def toString: String = s"$rule: $message"

final case class ServicePin(
    name: String,
    image: String,
    expose: Boolean = false,
    objectStorage: Boolean = false,
    env: Map[String, String] = Map.empty,
    instanceType: Option[String] = None,
    cpu: Option[String] = None,
    memory: Option[String] = None
):
  def tag: String = image.substring(image.lastIndexOf(':') + 1)

final case class TopicDeclaration(name: String, partitions: Int, retention: Retention)

final case class SecretRef(name: String, keys: Vector[String])

/** What a brand or a shared project is created from. */
sealed trait Manifest:
  def projectId: String
  def name: String
  def runtime: String
  def issuerBase: String
  def staffAudience: String
  def services: Vector[ServicePin]
  def topics: Vector[TopicDeclaration]
  def grantsMade: Vector[Grant]
  def secrets: Vector[SecretRef]
  def realMoney: Boolean

final case class BrandManifest(
    slug: String,
    name: String,
    realMoney: Boolean,
    frontendBaseUrl: Option[String],
    runtime: String,
    issuerBase: String,
    playerAudience: String,
    staffAudience: String,
    services: Vector[ServicePin],
    topics: Vector[TopicDeclaration],
    grantsMade: Vector[Grant],
    grantsReceived: Vector[Grant],
    secrets: Vector[SecretRef]
) extends Manifest:
  def projectId: String = slug

/** A shared project; its grants name `{brand}`, expanded over every brand the tool knows. */
final case class SharedManifest(
    projectId: String,
    name: String,
    runtime: String,
    issuerBase: String,
    staffAudience: String,
    services: Vector[ServicePin],
    topics: Vector[TopicDeclaration],
    grantsMade: Vector[Grant],
    secrets: Vector[SecretRef]
) extends Manifest:
  def realMoney: Boolean = false

  def grantsFor(brands: Vector[String]): Vector[Grant] =
    for
      brand <- brands
      grant <- grantsMade
    yield grant.copy(principal = grant.principal.replace("{brand}", brand))

object Manifest:

  private val mapper = ObjectMapper()

  private lazy val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

  private def schema(resource: String) =
    val stream = getClass.getResourceAsStream(s"/$resource")
    try factory.getSchema(stream)
    finally stream.close()

  private lazy val brandSchema  = schema("brand-manifest.v1.json")
  private lazy val sharedSchema = schema("shared-manifest.v1.json")

  def load(path: Path): Either[Vector[Problem], Manifest] =
    Try(Files.readString(path)).toEither.left
      .map(e => Vector(Problem("readable", s"$path cannot be read: ${e.getMessage}")))
      .flatMap(parse)

  /** Schema, then the rules a schema cannot say, all reported together. Nothing is called. */
  def parse(text: String): Either[Vector[Problem], Manifest] =
    Try(mapper.readTree(text)).toEither.left
      .map(e => Vector(Problem("json", s"the manifest is not JSON: ${e.getMessage}")))
      .flatMap { node =>
        val shared = node.has("project")
        val messages = (if shared then sharedSchema else brandSchema).validate(node).asScala.toVector
        if messages.nonEmpty then
          Left(messages.map { m =>
            // The refused value itself, so the refusal names the unknown entry (FR-010).
            val value = Option(m.getInstanceNode).filter(_.isValueNode).map(v => s" ('${v.asText()}')").getOrElse("")
            Problem("schema", s"${m.getMessage}$value")
          }.sortBy(_.message))
        else
          val manifest = if shared then sharedFrom(node) else brandFrom(node)
          val problems = rules(manifest)
          if problems.isEmpty then Right(manifest) else Left(problems)
      }

  // ── the rules beyond the schema ────────────────────────────────────────────

  def rules(manifest: Manifest): Vector[Problem] =
    val common =
      manifest.services.collect {
        case pin if pin.tag == "latest" =>
          Problem("image-tag", s"service '${pin.name}': an image pins a version; 'latest' does not")
      } ++
        manifest.services.collect {
          case pin if pin.expose && Layout.ServiceOnly.contains(pin.name) =>
            Problem("expose", s"service '${pin.name}' serves only other services and is never exposed")
        } ++
        manifest.services.collect {
          case pin if pin.objectStorage && !Layout.ObjectStorageServices.contains(pin.name) =>
            Problem("object-storage", s"service '${pin.name}' has no bucket; only ${Layout.ObjectStorageServices.toVector.sorted.mkString(" and ")} do")
        }
    common ++ (manifest match
      case brand: BrandManifest   => brandRules(brand)
      case shared: SharedManifest => sharedRules(shared))

  private def brandRules(brand: BrandManifest): Vector[Problem] =
    val names    = brand.services.map(_.name).toSet
    val required = Layout.BrandServices.filterNot(Layout.OptionalBrandServices.contains)
    val topics   = brand.topics.map(_.name).toSet
    val slug = Layout.slugProblems(brand.slug)
    // ankka's own rule second, so a slug Layout already refused is not refused twice.
    (if slug.nonEmpty then slug else ProjectId.problems(brand.slug)).map(Problem("slug", _)) ++
      required.filterNot(names).map(s => Problem("services", s"service '$s' is missing; a brand holds every service of FR-002")) ++
      Layout.BrandTopics.filterNot(topics).map(t => Problem("topics", s"topic '$t' is missing; a brand declares every topic of the contract")) ++
      brand.topics.collect {
        case t if t.retention != Layout.retentionOf(t.name) =>
          Problem("retention", s"topic '${t.name}' is declared ${show(t.retention)}; the contract says ${show(Layout.retentionOf(t.name))}")
      } ++
      differences("grants-made", brand.grantsMade, Layout.BrandGrantsMade) ++
      differences("grants-received", brand.grantsReceived, Layout.brandGrantsReceived(brand.slug))

  private def sharedRules(shared: SharedManifest): Vector[Problem] =
    val expected = Layout.SharedProjects.getOrElse(shared.projectId, Vector.empty)
    val names    = shared.services.map(_.name)
    val expectedGrants = Layout.brandGrantsReceived("{brand}").filter(g => granter(g) == shared.projectId)
    expected.filterNot(names.contains).map(s => Problem("services", s"service '$s' is missing; project '${shared.projectId}' holds ${expected.mkString(", ")}")) ++
      names.filterNot(expected.contains).map(s => Problem("services", s"service '$s' is not one of project '${shared.projectId}' (FR-003)")) ++
      Layout.SharedTopics.getOrElse(shared.projectId, Vector.empty).filterNot(shared.topics.map(_.name).contains)
        .map(t => Problem("topics", s"topic '$t' is missing")) ++
      shared.topics.map(_.name).filterNot(Layout.SharedTopics.getOrElse(shared.projectId, Vector.empty).contains)
        .map(t => Problem("topics", s"topic '$t' is not one of project '${shared.projectId}'")) ++
      differences("grants-made", shared.grantsMade, expectedGrants)

  /** The shared project whose service a received grant opens. */
  private def granter(grant: Grant): String = grant.target match
    case GrantTarget.Route(service, _, _) =>
      Layout.SharedProjects.collectFirst { case (p, ss) if ss.contains(service) => p }.getOrElse("")
    case GrantTarget.Topic(_, _) => ""
    case GrantTarget.Erasure     => "payments"

  private def differences(rule: String, actual: Vector[Grant], expected: Vector[Grant]): Vector[Problem] =
    actual.filterNot(expected.contains).map(g => Problem(rule, s"${describe(g)} is not a grant FR-016 allows")) ++
      expected.filterNot(actual.contains).map(g => Problem(rule, s"${describe(g)} is missing"))

  def describe(grant: Grant): String = grant.target match
    case GrantTarget.Route(s, m, p) => s"route $m $s$p to ${grant.principal}"
    case GrantTarget.Topic(t, r)    => s"topic $t ($r) to ${grant.principal}"
    case GrantTarget.Erasure        => s"the erasure right to ${grant.principal}"

  def show(retention: Retention): String = retention match
    case Retention.Fact    => "as a fact topic"
    case Retention.Days(d) => s"for $d day${if d == 1 then "" else "s"}"

  // ── reading a validated document ───────────────────────────────────────────

  val DefaultStaffAudience = "eitheror-casino"

  private def text(node: JsonNode, field: String): String = node.get(field).asText()

  private def optional(node: JsonNode, field: String): Option[String] = Option(node.get(field)).map(_.asText())

  private def services(node: JsonNode): Vector[ServicePin] =
    node.get("services").elements().asScala.toVector.map { s =>
      val env = Option(s.get("env")).map(_.properties().asScala.map(e => e.getKey -> e.getValue.asText()).toMap).getOrElse(Map.empty)
      val resources = Option(s.get("resources"))
      ServicePin(
        name = text(s, "name"),
        image = text(s, "image"),
        expose = Option(s.get("expose")).exists(_.asBoolean()),
        objectStorage = Option(s.get("objectStorage")).exists(_.asBoolean()),
        env = env,
        instanceType = resources.flatMap(r => Option(r.get("instanceType"))).map(_.asText()),
        cpu = resources.flatMap(r => Option(r.get("cpu"))).map(_.asText()),
        memory = resources.flatMap(r => Option(r.get("memory"))).map(_.asText())
      )
    }

  private def topics(node: JsonNode): Vector[TopicDeclaration] =
    node.get("topics").elements().asScala.toVector.map { t =>
      val retention = t.get("retention")
      TopicDeclaration(
        text(t, "name"),
        t.get("partitions").asInt(),
        if retention.isTextual then Retention.Fact else Retention.Days(retention.get("days").asInt())
      )
    }

  private def grants(node: JsonNode): Vector[Grant] =
    Option(node).map(_.elements().asScala.toVector).getOrElse(Vector.empty).map { g =>
      val target = g.get("target")
      val t =
        if target.has("route") then
          val r = target.get("route")
          GrantTarget.Route(text(r, "service"), text(r, "method"), text(r, "path"))
        else if target.has("topic") then
          val t = target.get("topic")
          GrantTarget.Topic(text(t, "name"), text(t, "right"))
        else GrantTarget.Erasure
      Grant(text(g, "principal"), t)
    }

  private def secrets(node: JsonNode): Vector[SecretRef] =
    Option(node.get("secrets")).map(_.elements().asScala.toVector).getOrElse(Vector.empty).map { s =>
      SecretRef(text(s, "name"), s.get("keys").elements().asScala.toVector.map(_.asText()))
    }

  private def brandFrom(node: JsonNode): BrandManifest =
    val brand = node.get("brand")
    BrandManifest(
      slug = text(brand, "slug"),
      name = text(brand, "name"),
      realMoney = brand.get("realMoney").asBoolean(),
      frontendBaseUrl = optional(brand, "frontendBaseUrl"),
      runtime = text(node.get("ankka"), "runtime"),
      issuerBase = text(node.get("keycloak"), "issuerBase"),
      playerAudience = optional(node.get("keycloak"), "playerAudience").getOrElse(s"casino-${text(brand, "slug")}"),
      staffAudience = optional(node.get("keycloak"), "staffAudience").getOrElse(DefaultStaffAudience),
      services = services(node),
      topics = topics(node),
      grantsMade = grants(node.get("grants").get("made")),
      grantsReceived = grants(node.get("grants").get("received")),
      secrets = secrets(node)
    )

  private def sharedFrom(node: JsonNode): SharedManifest =
    val project = node.get("project")
    SharedManifest(
      projectId = text(project, "id"),
      name = text(project, "name"),
      runtime = text(node.get("ankka"), "runtime"),
      issuerBase = text(node.get("keycloak"), "issuerBase"),
      staffAudience = optional(node.get("keycloak"), "staffAudience").getOrElse(DefaultStaffAudience),
      services = services(node),
      topics = topics(node),
      grantsMade = grants(node.get("grants").get("made")),
      secrets = secrets(node)
    )
