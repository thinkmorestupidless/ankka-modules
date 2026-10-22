package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

/**
 * Whether an installation may hold a brand that takes real money (spec 001 FR-035 to FR-038, FR-040),
 * read as spec 021 R6 has it: the core manifest's `requires[]`, evaluated over the vendor's installation
 * facts (`backups.target` …), the same facts the vendor's agent reads, so the two checks cannot drift.
 * The facts come from `GET /installation` when the control plane is asked, else from the overlay's
 * `settings.env` mapped to the same names. A fact not reported is unmet.
 */
object Readiness:

  enum Verdict:
    case Met, Failed, Unknown

  final case class Condition(requirement: String, name: String, verdict: Verdict, detail: String, source: String):
    def line: String = f"${verdict.toString.toLowerCase}%-8s $requirement $name: $detail ($source)"

  /** The installation's facts by the vendor's names, as text, and where they were read. */
  final case class Facts(values: Map[String, String], source: String):
    def get(name: String): Option[String] = values.get(name)

  /** One fact a requirement names that the installation does not meet, or does not report. */
  final case class Unmet(fact: String, wanted: String, actual: Option[String], source: String):
    override def toString: String =
      s"$fact must be ${if Minimums.contains(fact) then s"at least $wanted" else wanted}; ${actual.fold("not reported")(a => s"is $a")} (from $source)"

  /** Facts that are a minimum (vendor 004 `contracts/manifest.md`). */
  val Minimums: Set[String] = Set("erasure.keyringInstances", "broker.nodes", "broker.defaultCopies", "broker.minInSync")

  /**
   * The overlay's settings as the vendor's facts (t4-decisions, research R6): no new setting.
   * `BACKUP_POINT_IN_TIME` → `backups.target`; `BACKUP_OFF_CLUSTER_COPY_REQUIRED` → `backups.copyRequired` and,
   * negated, `backups.sharesFailureDomain`; `ERASURE_*` → `erasure`; `BROKER_REPLICAS`, `TOPIC_COPIES`,
   * `BROKER_MIN_IN_SYNC` → `broker`; `TOPIC_LONGEST_RETENTION` → `topics.longestRetention`.
   */
  def fromOverlay(i: Installation): Facts =
    val pairs = Vector(
      i.flag("BACKUP_POINT_IN_TIME").map(b => "backups.target" -> (if b then "object-store" else "none")),
      i.flag("BACKUP_OFF_CLUSTER_COPY_REQUIRED").map(b => "backups.copyRequired" -> b.toString),
      i.flag("BACKUP_OFF_CLUSTER_COPY_REQUIRED").map(b => "backups.sharesFailureDomain" -> (!b).toString),
      i.flag("ERASURE_ENABLED").map(b => "erasure.enabled" -> b.toString),
      i.int("ERASURE_KEYRING_REPLICAS").map(n => "erasure.keyringInstances" -> n.toString),
      i.int("BROKER_REPLICAS").map(n => "broker.nodes" -> n.toString),
      i.int("TOPIC_COPIES").map(n => "broker.defaultCopies" -> n.toString),
      i.int("BROKER_MIN_IN_SYNC").map(n => "broker.minInSync" -> n.toString),
      i.get("TOPIC_LONGEST_RETENTION").map(v => "topics.longestRetention" -> (if v == "forever" then "unbounded" else v))
    ).flatten
    Facts(pairs.toMap, i.source.toString)

  /**
   * `GET /installation` as ankka reports it (control plane reference): `backups.backupTarget`,
   * `copyRequired`, `sharesFailureDomain` today; `erasure`, `broker` and `topics` once ankka reports them
   * (ankka-vendor's request 4 of ankka). What is absent stays absent, and so unmet.
   */
  def fromControlPlane(json: String, source: String): Facts =
    val node = ObjectMapper().readTree(json)
    def text(n: JsonNode) = Option(n).filterNot(_.isNull).filterNot(_.isMissingNode).map(_.asText())
    val backups = node.path("backups")
    val pairs = Vector(
      text(backups.get("target")).orElse(text(backups.get("backupTarget"))).map("backups.target" -> _),
      text(backups.get("copyRequired")).map("backups.copyRequired" -> _),
      text(backups.get("sharesFailureDomain")).map("backups.sharesFailureDomain" -> _),
      text(node.path("erasure").get("enabled")).map("erasure.enabled" -> _),
      text(node.path("erasure").get("keyringInstances")).map("erasure.keyringInstances" -> _),
      text(node.path("broker").get("nodes")).map("broker.nodes" -> _),
      text(node.path("broker").get("defaultCopies")).map("broker.defaultCopies" -> _),
      text(node.path("broker").get("minInSync")).map("broker.minInSync" -> _),
      text(node.path("topics").get("longestRetention")).map("topics.longestRetention" -> _)
    ).flatten
    Facts(pairs.toMap, source)

  private def days(duration: String): Option[Long] =
    val n = duration.dropRight(1).toLongOption
    duration.lastOption.flatMap {
      case 'd' => n
      case 'h' => n.map(_ / 24)
      case 'm' => n.map(_ / (24 * 60))
      case 's' => n.map(_ / (24 * 3600))
      case _   => None
    }

  private def meets(fact: String, wanted: JsonNode, actual: String): Boolean =
    if fact == "topics.longestRetention" then
      if wanted.asText() == "unbounded" then actual == "unbounded"
      else actual == "unbounded" || (for a <- days(actual); w <- days(wanted.asText()) yield a >= w).getOrElse(false)
    else if Minimums.contains(fact) then actual.toLongOption.exists(_ >= wanted.asLong())
    else actual == wanted.asText()

  /** Every fact of one requirement the installation does not meet, in the manifest's order. */
  def unmet(requirement: ModuleManifest.Requirement, facts: Facts): Vector[Unmet] =
    requirement.facts.collect {
      case (fact, wanted) if !facts.get(fact).exists(meets(fact, wanted, _)) => Unmet(fact, wanted.asText(), facts.get(fact), facts.source)
    }

  /** Whether a requirement's `when` holds against the values the brand's project holds. */
  def applies(requirement: ModuleManifest.Requirement, values: Map[String, String]): Boolean =
    requirement.when.forall((key, equals) => values.get(key).contains(equals))

  /** The core's manifest as the contract artifact carries it. */
  lazy val core: ModuleManifest =
    val in = getClass.getResourceAsStream("/modules/core/module.json")
    val text = try String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()
    ModuleManifest.parse(text).fold(rs => throw IllegalStateException(rs.mkString("; ")), identity)

  /** The unmet facts of every requirement of `requiring` that holds for these values. */
  def check(requiring: Vector[ModuleManifest.Requirement], values: Map[String, String], facts: Facts): Vector[Unmet] =
    requiring.filter(applies(_, values)).flatMap(unmet(_, facts))

  /** The facts of spec 001's four conditions, by the vendor's names. */
  private val Groups = Vector(
    ("FR-035", "the backups", "backups."),
    ("FR-036", "the erasure of personal data", "erasure."),
    ("FR-037", "the retention of facts", "topics."),
    ("FR-038", "the copies of its facts", "broker.")
  )

  /** The core's real-money requirement as spec 001's four conditions, from the overlay's settings. */
  def conditions(installation: Installation): Vector[Condition] = conditions(fromOverlay(installation))

  def conditions(facts: Facts): Vector[Condition] =
    val requirement = core.requires.find(_.when.contains("casino-real-money" -> "true")).getOrElse(throw IllegalStateException("the core declares no real-money requirement"))
    Groups.map { (fr, name, prefix) =>
      val named   = requirement.facts.filter(_._1.startsWith(prefix))
      val failing = unmet(requirement.copy(facts = named), facts)
      val verdict =
        if failing.isEmpty then Verdict.Met
        else if failing.exists(_.actual.isDefined) then Verdict.Failed
        else Verdict.Unknown
      val detail =
        if failing.isEmpty then named.map((f, _) => s"$f ${facts.get(f).getOrElse("")}").mkString(", ")
        else failing.map(u => s"${u.fact} ${u.actual.getOrElse("not stated")}, wanted ${if Minimums.contains(u.fact) then "at least " else ""}${u.wanted}").mkString(", ")
      Condition(fr, name, verdict, detail, facts.source)
    }

  /** Spec 001's condition a fact belongs to: `FR-035 the backups` for `backups.target`. */
  def conditionOf(fact: String): String =
    Groups.find((_, _, prefix) => fact.startsWith(prefix)).fold(fact)((fr, name, _) => s"$fr $name")

  def fit(conditions: Vector[Condition]): Boolean = conditions.forall(_.verdict == Verdict.Met)

  /** The refusals for a real-money brand, one per unmet condition, naming it. */
  def refusals(conditions: Vector[Condition]): Vector[Problem] =
    conditions.filterNot(_.verdict == Verdict.Met).map { c =>
      Problem("real-money", s"${c.requirement} ${c.name}: ${c.detail} (${c.verdict.toString.toLowerCase}, from ${c.source})")
    }
