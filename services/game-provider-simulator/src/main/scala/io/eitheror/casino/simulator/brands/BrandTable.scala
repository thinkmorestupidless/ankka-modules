package io.eitheror.casino.simulator.brands

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.eitheror.casino.simulator.core.Brands

import java.net.URI
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** One brand the simulator serves (contracts/brand-config.md). */
final case class Brand(
    slug: String,
    baseAddress: URI,
    testBrand: Boolean,
    providerWaitMillis: Long,
    keyRefreshBoundSeconds: Long,
    caPem: Option[String],
    registerKeyAt: Option[URI],
    site: String
)

/**
 * The brands the simulator serves (FR-025), from `SIMULATOR_BRANDS`, and the simulator's own
 * settings. A malformed entry refuses the start, naming the field.
 */
final case class BrandTable(
    brands: Vector[Brand],
    externalUrl: String,
    retentionDays: Int,
    gamesExtra: Option[String]
) extends Brands:
  private val bySlug = brands.map(b => b.slug -> b).toMap

  def apply(slug: String): Brand          = bySlug.getOrElse(slug, throw java.util.NoSuchElementException(s"unknown brand $slug"))
  def get(slug: String): Option[Brand]    = bySlug.get(slug)
  def known(slug: String): Boolean        = bySlug.contains(slug)
  def testBrand(slug: String): Boolean    = bySlug.get(slug).exists(_.testBrand)
  def answerWait(slug: String): FiniteDuration      = bySlug.get(slug).map(_.providerWaitMillis.millis).getOrElse(5.seconds)
  def keyRefreshBound(slug: String): FiniteDuration = bySlug.get(slug).map(_.keyRefreshBoundSeconds.seconds).getOrElse(60.seconds)
  def slugs: Vector[String]               = brands.map(_.slug)

  /**
   * The brand a launch came from. The stand-in's launch names no brand, only the brand's `site`
   * (a string, or an object with an `id`): matched against each brand's `site`, which defaults to
   * its slug; with one brand configured, that brand.
   */
  def bySite(site: Option[String]): Option[Brand] =
    site.flatMap(s => brands.find(_.site == s)).orElse(Option.when(brands.size == 1)(brands.head))

object BrandTable:

  private val mapper = ObjectMapper()

  /** From the environment, as the brands tool renders a manifest's `env`, with `env:` references resolved there too. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, BrandTable] =
    parse(
      env.getOrElse("SIMULATOR_BRANDS", "[]"),
      env.getOrElse("SIMULATOR_EXTERNAL_URL", "http://localhost:9000"),
      env.get("SIMULATOR_RETENTION_DAYS"),
      env.get("SIMULATOR_GAMES_EXTRA"),
      env
    )

  def parse(brands: String, externalUrl: String, retention: Option[String], gamesExtra: Option[String], env: Map[String, String]): Either[String, BrandTable] =
    for
      root <- Try(mapper.readTree(brands)).toEither.left.map(e => s"SIMULATOR_BRANDS is not JSON: ${e.getMessage}")
      _    <- Either.cond(root.isArray, (), "SIMULATOR_BRANDS must be a JSON array of brands")
      list <- root.elements().asScala.toVector.zipWithIndex.foldLeft[Either[String, Vector[Brand]]](Right(Vector.empty)) {
        case (acc, (node, i)) => acc.flatMap(done => brand(node, i, env).map(done :+ _))
      }
      _    <- list.groupBy(_.slug).collectFirst { case (s, bs) if bs.size > 1 => s }.toLeft(()).left.map(s => s"SIMULATOR_BRANDS names brand '$s' twice")
      days <- retention.fold[Either[String, Int]](Right(30))(r => r.toIntOption.filter(_ > 0).toRight(s"SIMULATOR_RETENTION_DAYS '$r' is not a positive number of days"))
    yield BrandTable(list, externalUrl.stripSuffix("/"), days, gamesExtra.filter(_.trim.nonEmpty))

  private def brand(n: JsonNode, i: Int, env: Map[String, String]): Either[String, Brand] =
    def at(field: String)                                = s"SIMULATOR_BRANDS[$i].$field"
    def text(field: String): Option[String]              = Option(n.get(field)).filter(!_.isNull).map(_.asText())
    def required(field: String): Either[String, String]  = text(field).filter(_.nonEmpty).toRight(s"${at(field)} is required")
    def uri(field: String, value: String): Either[String, URI] =
      Try(URI.create(value)).toOption.filter(u => u.getScheme == "http" || u.getScheme == "https").toRight(s"${at(field)} '$value' is not an http or https address")
    def number(field: String, default: Long): Either[String, Long] =
      Option(n.get(field)).filter(!_.isNull).fold[Either[String, Long]](Right(default)) { v =>
        if v.canConvertToLong && v.asLong() > 0 then Right(v.asLong()) else Left(s"${at(field)} must be a positive whole number")
      }
    def resolved(field: String): Either[String, Option[String]] =
      text(field) match
        case Some(ref) if ref.startsWith("env:") =>
          env.get(ref.stripPrefix("env:")).filter(_.nonEmpty).map(Some(_)).toRight(s"${at(field)} names ${ref.stripPrefix("env:")}, which is not set")
        case other => Right(other)
    for
      _        <- Either.cond(n.isObject, (), s"SIMULATOR_BRANDS[$i] must be an object")
      slug     <- required("slug").filterOrElse(_.matches("[a-z][a-z0-9-]*"), s"${at("slug")} must be a brand slug")
      base     <- required("baseAddress").flatMap(uri("baseAddress", _))
      test     <- Option(n.get("testBrand")).filter(_.isBoolean).map(_.asBoolean()).toRight(s"${at("testBrand")} is required: true or false")
      wait     <- number("providerWaitMillis", 5000)
      bound    <- number("keyRefreshBoundSeconds", 60)
      ca       <- resolved("caPem")
      register <- text("registerKeyAt").fold[Either[String, Option[URI]]](Right(None))(v => uri("registerKeyAt", v).map(Some(_)))
    yield Brand(slug, base, test, wait, bound, ca, register, text("site").filter(_.nonEmpty).getOrElse(slug))
