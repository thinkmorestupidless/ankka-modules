package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.ObjectMapper

import scala.util.Try

/**
 * Whether the installation's platform can run the runtimes an apply names (spec 031 FR-024,
 * contracts/runtime-check.md). The rule is ankka's: a platform runs a runtime of its own major
 * version whose minor is its own or the one below; patch versions are not compared. A platform
 * whose version is not reported runs nothing, as a fact not reported is unmet (Readiness).
 */
object Runtimes:

  /** The platform's version and where it was read. */
  final case class Platform(version: Option[String], source: String)

  private def majorMinor(version: String): Option[(Int, Int)] =
    version.trim.stripPrefix("v").takeWhile(c => c.isDigit || c == '.').split('.') match
      case Array(major, minor, _*) => for a <- major.toIntOption; b <- minor.toIntOption yield (a, b)
      case _                       => None

  def supports(platform: String, runtime: String): Boolean =
    (majorMinor(platform), majorMinor(runtime)) match
      case (Some((pMajor, pMinor)), Some((rMajor, rMinor))) => pMajor == rMajor && (rMinor == pMinor || rMinor == pMinor - 1)
      case _                                                => false

  /** The runtimes of `named` that a platform at `platform` cannot run; every one when it is not reported. */
  def unsupported(platform: Option[String], named: Set[String]): Set[String] =
    platform.fold(named)(p => named.filterNot(supports(p, _)))

  /**
   * The platform's version: `GET /installation`'s `platformVersion` when the control plane is asked and
   * reports it, else `PLATFORM_VERSION` in the overlay. Read on its own, not through `Readiness.Facts`, so
   * asking for it never changes where the real-money facts come from.
   */
  def platform(installation: Installation, cp: Option[ControlPlane]): Platform =
    val reported = cp.flatMap(c => Try(c.installation()).toOption.flatten).flatMap { json =>
      Try(ObjectMapper().readTree(json).path("platformVersion")).toOption
        .filter(n => n.isTextual && n.asText().trim.nonEmpty).map(_.asText().trim)
    }
    reported match
      case Some(v) => Platform(Some(v), "GET /installation")
      case None    => Platform(installation.get("PLATFORM_VERSION"), installation.source.toString)

  /**
   * The refusal for the runtimes `byService` names (`module/service -> runtime`) that the installation's
   * platform cannot run, as the contract words it; empty when every one is supported.
   */
  def refusal(installation: Installation, platform: Platform, byService: Vector[(String, String)]): Vector[String] =
    val bad = unsupported(platform.version, byService.map(_._2).toSet)
    if bad.isEmpty then Vector.empty
    else
      val head = platform.version match
        case Some(v) =>
          s"refused: the installation ${installation.name} runs platform $v, which cannot run runtime ${bad.toVector.sorted.mkString(", ")}"
        case None =>
          s"refused: the installation ${installation.name} reports no platform version (from ${platform.source})"
      Vector(
        head,
        s"  (named by: ${byService.filter((_, r) => bad.contains(r)).map(_._1).mkString(", ")})",
        "  a platform runs runtimes of its own minor version and the one below"
      )
