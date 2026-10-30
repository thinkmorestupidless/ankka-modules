package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.controlplane.api.ProjectId
import io.eitheror.casino.contract.Layout
import io.eitheror.casino.modules.ModuleManifest.Refusal

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * What one brand supplies (spec 021 FR-003, contracts/values.md): `brands/<slug>/values.json`. No
 * version, service, grant or secret value: those are the modules', the entitlement's and the
 * brand's secrets file's.
 */
final case class Values(
    slug: String,
    name: String,
    realMoney: Boolean,
    issuerBase: String,
    hostnames: Map[String, String],
    secrets: Vector[SecretRef],
    values: Map[String, String]
):
  /** The secrets entry a secret key is read from, if this brand declares it. */
  def secretFor(key: KeyRow): Option[(String, String)] =
    key.source(slug).filter((secret, entry) => secrets.exists(s => s.name == secret && s.keys.contains(entry)))

object Values:

  /** Spec 001's manifest fields, refused by name: they are a module's or the entitlement's. */
  val ModuleFields: Vector[String] = Vector("version", "ankka", "services", "topics", "grants")

  val StaffAudience = "eitheror-casino"

  private val mapper = ObjectMapper()

  private lazy val schema =
    val in = getClass.getResourceAsStream("/values.v1.json")
    try JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in)
    finally in.close()

  /** What the rules need to know of the contract: its keys and the services a module lists. */
  final case class View(keys: Keys, services: Set[String])

  /**
   * The tenant project's id (contracts/values.md): `<organization>-vendor` as the vendor provisions
   * it, or, locally, the slug as spec 001 had it. The one derivation.
   */
  def tenantProject(slug: String, organization: Option[String]): String =
    organization.fold(slug)(o => s"$o-vendor")

  /**
   * The derived keys (E1), from the values, the project and the installation: what spec 001's tool
   * computed into every descriptor, now written as keys by `values push` since the vendor's format
   * has no templating.
   */
  def derived(v: Values, project: String, installation: Option[Installation]): Map[String, String] =
    val issuer = v.issuerBase.stripSuffix("/")
    val keys   = installation.flatMap(_.get("KEYCLOAK_JWKS_BASE")).getOrElse(v.issuerBase).stripSuffix("/")
    val realm  = s"casino-${v.slug}"
    Map(
      "casino-brand"         -> v.slug,
      "casino-project"       -> project,
      "casino-real-money"    -> v.realMoney.toString,
      "keycloak-issuer-base" -> v.issuerBase,
      "keycloak-jwks-base"   -> keys,
      "auth-realm"           -> realm,
      "auth-player-issuer"   -> s"$issuer/realms/$realm",
      "auth-player-jwks-url" -> s"$keys/realms/$realm/protocol/openid-connect/certs",
      "auth-player-audience" -> realm,
      "auth-staff-issuer"    -> s"$issuer/realms/eitheror",
      "auth-staff-jwks-url"  -> s"$keys/realms/eitheror/protocol/openid-connect/certs",
      // the shared payments' cashier edge as the installation names it; empty where it names none, and
      // players then answers a deposit 503 naming ingest (spec 008 R15)
      "ingest-url"           -> installation.flatMap(_.get("INGEST_URL")).getOrElse("")
    )

  /**
   * Keys a module reads that have a value when the brand writes none (contract/keys.md; spec 012
   * research R7, R12; spec 009 R18): the largest KYC upload, the reporting zone, and the modules the brand runs, which only the local plan
   * knows and fills in (`Render.plan`).
   */
  val defaults: Map[String, String] = Map("reporting-zone" -> "UTC", "casino-modules" -> "core", "kyc-max-upload-bytes" -> "5242880")

  /** Every non-secret value a module may read: the defaults, the brand's own and the derived. */
  def all(v: Values, project: String, installation: Option[Installation]): Map[String, String] =
    defaults ++ v.values ++ derived(v, project, installation)

  def load(path: Path, view: View): Either[Vector[Refusal], Values] =
    Try(Files.readString(path)).toEither.left
      .map(e => Vector(Refusal("", s"$path cannot be read: ${e.getMessage}")))
      .flatMap(text => parse(text, view, Option(path.getParent).map(_.getFileName.toString).filter(_ => path.getFileName.toString == "values.json")))

  /** The named refusals first, then the schema, then the contract's rules, all reported together. */
  def parse(text: String, view: View, directory: Option[String] = None): Either[Vector[Refusal], Values] =
    Try(mapper.readTree(text)).toEither.left
      .map(e => Vector(Refusal("", s"the values file is not JSON: ${e.getMessage}")))
      .flatMap {
        case node: ObjectNode =>
          val moduleFields = ModuleFields.filter(node.has).map(f => Refusal(f, s"'$f' is the module's, not the brand's"))
          val rest = node.deepCopy()
          ModuleFields.foreach(rest.remove)
          val messages = schema.validate(rest).asScala.toVector.map { m =>
            val at    = m.getInstanceLocation.toString.stripPrefix("$").stripPrefix(".")
            val value = Option(m.getInstanceNode).filter(_.isValueNode).map(n => s" ('${n.asText()}')").getOrElse("")
            Refusal(at, s"${m.getMessage}$value")
          }.distinct
          if messages.nonEmpty then Left(moduleFields ++ messages)
          else
            val v = from(rest)
            val problems = moduleFields ++ rules(v, view, directory)
            if problems.isEmpty then Right(v) else Left(problems)
        case _ => Left(Vector(Refusal("", "the values file is not a JSON object")))
      }

  private def from(node: JsonNode): Values =
    def map(field: String) =
      Option(node.get(field)).map(_.properties().asScala.map(e => e.getKey -> e.getValue.asText()).toMap).getOrElse(Map.empty)
    Values(
      slug = node.path("brand").path("slug").asText(),
      name = node.path("brand").path("name").asText(),
      realMoney = node.path("brand").path("realMoney").asBoolean(),
      issuerBase = node.path("keycloak").path("issuerBase").asText(),
      hostnames = map("hostnames"),
      secrets = Option(node.get("secrets")).map(_.elements().asScala.toVector).getOrElse(Vector.empty).map { s =>
        SecretRef(s.path("name").asText(), s.path("keys").elements().asScala.toVector.map(_.asText()))
      },
      values = map("values")
    )

  def rules(v: Values, view: View, directory: Option[String]): Vector[Refusal] =
    val layout = Layout.slugProblems(v.slug)
    val slug = (if layout.nonEmpty then layout else ProjectId.problems(v.slug)).map(Refusal("brand.slug", _)) ++
      directory.filter(_ != v.slug).map(d => Refusal("brand.slug", s"slug '${v.slug}' is in directory '$d'")).toVector
    val hostnames = v.hostnames.keys.toVector.sorted.collect {
      case s if !view.services.contains(s) => Refusal(s"hostnames.$s", s"service '$s' is not in the contract")
    }
    val values = v.values.toVector.sortBy(_._1).flatMap { (k, value) =>
      val at = s"values.$k"
      view.keys.named(k) match
        case None                     => Vector(Refusal(at, s"configuration key '$k' is not in the contract"))
        case Some(row) if row.secret  => Vector(Refusal(at, s"'$k' is a secret key: set it with secrets"))
        case Some(row) if row.derived => Vector(Refusal(at, s"'$k' is derived from ${row.derivedFrom.get}; the brand does not write it"))
        case Some(_) =>
          Option.when(ModuleManifest.looksLikeSecret(k, value))(Refusal(at, s"'$value' looks like a secret")).toVector
    }
    slug ++ hostnames ++ values

  /** Every secret key of the modules named that this brand's secrets do not cover (contracts/values.md). */
  def uncovered(v: Values, keys: Keys, secretKeys: Set[String]): Vector[Refusal] =
    secretKeys.toVector.sorted.flatMap(k => keys.named(k)).collect {
      case row if v.secretFor(row).isEmpty => Refusal("secrets", s"secret key '${row.key}' has no secret")
    }
