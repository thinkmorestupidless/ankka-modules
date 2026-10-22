package io.eitheror.casino.modules

/**
 * Whether an installation may hold a brand that takes real money (spec 001 FR-035 to FR-038,
 * FR-040). ankka refuses nothing for want of these (research R8), so the tool decides, before any
 * call. A condition it cannot read is `Unknown`, and refuses as `Failed` does.
 */
object Readiness:

  enum Verdict:
    case Met, Failed, Unknown

  final case class Condition(requirement: String, name: String, verdict: Verdict, detail: String, source: String):
    def line: String = f"${verdict.toString.toLowerCase}%-8s $requirement $name: $detail ($source)"

  /** The four conditions, in the order the spec lists them. */
  def conditions(installation: Installation): Vector[Condition] =
    val src = installation.source.toString
    def check(requirement: String, name: String)(read: => Option[(Boolean, String)]): Condition =
      read match
        case Some((true, d))  => Condition(requirement, name, Verdict.Met, d, src)
        case Some((false, d)) => Condition(requirement, name, Verdict.Failed, d, src)
        case None             => Condition(requirement, name, Verdict.Unknown, "not stated", src)
    Vector(
      check("FR-035", "the backups") {
        for
          pitr <- installation.flag("BACKUP_POINT_IN_TIME")
          off  <- installation.flag("BACKUP_OFF_CLUSTER_COPY_REQUIRED")
        yield (pitr && off, s"point-in-time recovery ${onOff(pitr)}, a copy outside the cluster ${if off then "required" else "not required"}")
      },
      check("FR-036", "the erasure of personal data") {
        for
          on      <- installation.flag("ERASURE_ENABLED")
          keyring <- installation.int("ERASURE_KEYRING_REPLICAS")
        yield (on && keyring >= 2, s"erasure ${onOff(on)}, keyring at $keyring instance${plural(keyring)}")
      },
      check("FR-037", "the retention of facts") {
        installation.longestRetentionDays.map {
          case None       => (true, "facts may be kept for ever")
          case Some(days) => (false, s"facts are kept at most $days day${plural(days)}")
        }
      },
      check("FR-038", "the copies of its facts") {
        for
          nodes  <- installation.int("BROKER_REPLICAS")
          copies <- installation.int("TOPIC_COPIES")
          sync   <- installation.int("BROKER_MIN_IN_SYNC")
        yield (nodes >= 3 && copies >= 3 && sync >= 2, s"$nodes broker node${plural(nodes)}, $copies cop${if copies == 1 then "y" else "ies"} of each fact, $sync in sync")
      }
    )

  def fit(conditions: Vector[Condition]): Boolean = conditions.forall(_.verdict == Verdict.Met)

  /** The refusals for a real-money manifest, one per unmet condition, naming it. */
  def refusals(conditions: Vector[Condition]): Vector[Problem] =
    conditions.filterNot(_.verdict == Verdict.Met).map { c =>
      Problem("real-money", s"${c.requirement} ${c.name}: ${c.detail} (${c.verdict.toString.toLowerCase}, from ${c.source})")
    }

  private def onOff(b: Boolean) = if b then "on" else "off"
  private def plural(n: Int)    = if n == 1 then "" else "s"
