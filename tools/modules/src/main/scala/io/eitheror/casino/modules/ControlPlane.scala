package io.eitheror.casino.modules

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.{GrantRequest as _, *}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.io.FileInputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.{Duration, Instant}
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.collection.mutable

/** A refusal from the control plane, with its HTTP status (0 when it could not be reached). */
final case class ControlPlaneError(status: Int, message: String) extends RuntimeException(message)

/** A topic's declaration as the tool sends it: the JSON of `PUT /projects/{id}/topics/{name}`. */
final case class TopicRequest(partitions: Int, contract: Option[(String, String)]):
  /** The body. The schema is passed through as the JSON document it already is. */
  def json: String =
    val c = contract.fold("")((name, schema) => s""","contract":{"name":"$name","schema":$schema}""")
    s"""{"partitions":$partitions,"compacted":false$c}"""

/**
 * What the tool asks of an ankka control plane. Grants (ankka 040) answer `None` when the platform
 * does not have them, which the tool reports as "platform lacks 040" rather than failing.
 */
trait ControlPlane:
  def project(id: String): Option[ProjectSummary]
  def createProject(id: String, name: String, organization: String): Unit
  def topics(project: String): Vector[ProjectTopic]
  def declareTopic(project: String, name: String, request: TopicRequest): Unit
  def secrets(project: String): Vector[ProjectSecretSummary]
  def setSecret(project: String, name: String, entries: Map[String, String]): Unit
  def services(project: String): Vector[ServiceStatus]
  def descriptor(project: String, name: String, generation: Long): Option[ServiceDescriptor]
  def history(project: String, name: String): Vector[HistoryEntry]
  def apply(project: String, descriptor: ServiceDescriptor): ServiceStatus
  def expose(project: String, name: String): ServiceStatus
  def grants(project: String): Option[Vector[GrantStatus]]
  def makeGrant(project: String, grant: GrantRequest): Option[Unit]
  def receivedGrants(project: String): Option[Vector[GrantStatus]]
  /** `GET /installation`: what the installation reports of itself (spec 021 R6), `None` where it does not answer. */
  def installation(): Option[String]
  /** `PUT /services/{p}/{name}/hostnames/{hostname}`: a custom hostname; `None` where the platform lacks them. */
  def addHostname(project: String, service: String, hostname: String): Option[Unit]
  /** `DELETE /services/{p}/{name}`: `false` when there was none. */
  def delete(project: String, name: String): Boolean
  /** Every call made, in order, for the `calls: N` line and the tests. */
  def calls: Vector[String]

/** A grant as ankka 040 shapes it (spec 040 R20): principal, target, and its effect. */
final case class GrantRequest(principal: String, target: String)
final case class GrantStatus(principal: String, target: String, effect: String)

/**
 * Where the tool's bearer comes from. A token given (`ANKKA_TOKEN`, the config's `token`) is used as it
 * is. The CLI's login is read again before each call, and renewed through the CLI when it is within
 * [[Bearer.Margin]] of expiring or the control plane refuses it: `apply` waits on Ready for longer than
 * the access token lives (five minutes on dev-kind).
 */
trait Bearer:
  def current(): Option[String]

  /** Asks for a new token; true when the next [[current]] may differ. */
  def renew(): Boolean

object Bearer:
  val Margin: Duration = Duration.ofSeconds(30)

  final case class Given(token: Option[String]) extends Bearer:
    def current(): Option[String] = token
    def renew(): Boolean          = false

  /**
   * The access token `ankka login` saved in `credentials.json` under `url`, renewed by `refresh` (by
   * default `ankka whoami`, which refreshes and saves it), so the CLI keeps owning its refresh token.
   */
  final class Login(credentials: Path, url: String, refresh: () => Unit = () => Bearer.whoami(),
      now: () => Instant = () => Instant.now()) extends Bearer:
    def current(): Option[String] =
      if saved().exists((_, expires) => expires.exists(e => !now().plus(Margin).isBefore(e))) then refresh()
      saved().map(_._1)

    def renew(): Boolean = { refresh(); true }

    private def saved(): Option[(String, Option[Instant])] =
      if !Files.exists(credentials) then None
      else
        scala.util.Try {
          val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(credentials))
          Option(node.get(url.stripSuffix("/"))).flatMap { n =>
            Option(n.get("accessToken")).filter(_.isTextual).map(t =>
              t.asText() -> Option(n.get("expiresAt")).filter(_.canConvertToLong).map(e => Instant.ofEpochSecond(e.asLong())))
          }
        }.toOption.flatten

  /** `ankka whoami`, quietly: its output is not the tool's, and a failure leaves the saved token as it was. */
  def whoami(): Unit =
    scala.util.Try {
      val p = ProcessBuilder("ankka", "whoami").redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
      if !p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) then p.destroyForcibly(): Unit
    }: Unit

/** The HTTP control plane, configured as the `ankka` CLI is: flag, then `ANKKA_*`, then its config. */
final class HttpControlPlane(url: String, bearer: Bearer, ca: Option[String]) extends ControlPlane:

  def this(url: String, token: Option[String], ca: Option[String]) = this(url, Bearer.Given(token), ca)

  private val log = mutable.ArrayBuffer.empty[String]
  def calls: Vector[String] = log.toVector

  private val client =
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
    ca.foreach(path => builder.sslContext(HttpControlPlane.trusting(path)))
    builder.build()

  private def segment(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")

  /** One call; a `401` renews the bearer and asks once more. */
  private def send(method: String, path: String, body: Option[String]): (Int, String) =
    log += s"$method $path"
    val first = attempt(method, path, body)
    if first._1 == 401 && bearer.renew() then attempt(method, path, body) else first

  private def attempt(method: String, path: String, body: Option[String]): (Int, String) =
    val builder = HttpRequest.newBuilder(URI.create(url.stripSuffix("/") + path)).timeout(Duration.ofSeconds(60))
    bearer.current().foreach(t => builder.header("Authorization", s"Bearer $t"))
    body match
      case Some(b) => builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(b))
      case None    => builder.method(method, HttpRequest.BodyPublishers.noBody())
    try
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      (response.statusCode, response.body)
    catch case e: java.io.IOException => throw ControlPlaneError(0, s"could not reach $url: ${e.getMessage}")

  private def ok(method: String, path: String, body: Option[String] = None): String =
    val (status, text) = send(method, path, body)
    if status >= 200 && status < 300 then text else throw ControlPlaneError(status, s"$method $path: $status $text")

  /** 404 means absent; anything else outside 2xx is an error. */
  private def maybe(method: String, path: String, body: Option[String] = None): Option[String] =
    val (status, text) = send(method, path, body)
    if status == 404 then None
    else if status >= 200 && status < 300 then Some(text)
    else throw ControlPlaneError(status, s"$method $path: $status $text")

  def project(id: String) = maybe("GET", s"/projects/${segment(id)}").map(readFromString[ProjectSummary](_))
  def createProject(id: String, name: String, organization: String) =
    ok("POST", s"/projects/${segment(id)}", Some(writeToString(CreateProject(name, organization)))): Unit
  def topics(project: String) = readFromString[Vector[ProjectTopic]](ok("GET", s"/projects/${segment(project)}/topics"))
  def declareTopic(project: String, name: String, request: TopicRequest) =
    ok("PUT", s"/projects/${segment(project)}/topics/${segment(name)}", Some(request.json)): Unit
  def secrets(project: String) = readFromString[Vector[ProjectSecretSummary]](ok("GET", s"/projects/${segment(project)}/secrets"))
  def setSecret(project: String, name: String, entries: Map[String, String]) =
    ok("PUT", s"/projects/${segment(project)}/secrets/${segment(name)}", Some(writeToString(SetProjectSecret(entries)))): Unit
  def services(project: String) = readFromString[Vector[ServiceStatus]](ok("GET", s"/services/${segment(project)}"))
  def descriptor(project: String, name: String, generation: Long) =
    maybe("GET", s"/services/${segment(project)}/${segment(name)}/descriptor?generation=$generation").map(readFromString[ServiceDescriptor](_))
  def history(project: String, name: String) =
    readFromString[Vector[HistoryEntry]](ok("GET", s"/services/${segment(project)}/${segment(name)}/history"))
  def apply(project: String, descriptor: ServiceDescriptor) =
    readFromString[ServiceStatus](ok("PUT", s"/services/${segment(project)}/${segment(descriptor.name)}", Some(writeToString(descriptor))))
  def expose(project: String, name: String) =
    readFromString[ServiceStatus](ok("POST", s"/services/${segment(project)}/${segment(name)}/expose"))

  // ankka 040 is specified, not built: until it is, these routes answer 404 and the tool says so.
  def grants(project: String) = maybe("GET", s"/projects/${segment(project)}/grants").map(GrantJson.statuses)
  def makeGrant(project: String, grant: GrantRequest) =
    maybe("POST", s"/projects/${segment(project)}/grants", Some(GrantJson.request(grant))).map(_ => ())
  def receivedGrants(project: String) = maybe("GET", s"/projects/${segment(project)}/grants/received").map(GrantJson.statuses)
  def installation() = maybe("GET", "/installation")
  def addHostname(project: String, service: String, hostname: String) =
    maybe("PUT", s"/services/${segment(project)}/${segment(service)}/hostnames/${segment(hostname)}").map(_ => ())
  def delete(project: String, name: String) = maybe("DELETE", s"/services/${segment(project)}/${segment(name)}").isDefined

object HttpControlPlane:

  /** Configured as the `ankka` CLI is configured, so one `ankka login` serves both. */
  def fromEnvironment(): HttpControlPlane =
    val (url, bearer, ca) = resolve()
    HttpControlPlane(url, bearer, ca)

  /**
   * The control plane's address, token and authority: `ANKKA_*`, then the CLI's `config.json`, and for the
   * token last the access token `ankka login` saved in `credentials.json` under that address.
   */
  def settings(env: Map[String, String] = sys.env, home: Path = Paths.get(sys.props("user.home"))): (String, Option[String], Option[String]) =
    val (url, bearer, ca) = resolve(env, home, () => ())
    (url, bearer.current(), ca)

  /** As [[settings]], with the token as a [[Bearer]]: the CLI's login is renewed by `refresh`, a given token never. */
  def resolve(env: Map[String, String] = sys.env, home: Path = Paths.get(sys.props("user.home")),
      refresh: () => Unit = () => Bearer.whoami()): (String, Bearer, Option[String]) =
    val dir = home.resolve(".ankka")
    val saved = dir.resolve("config.json")
    val config =
      if Files.exists(saved) then scala.util.Try(ujsonLite(Files.readString(saved))).getOrElse(Map.empty)
      else Map.empty[String, String]
    val url = env.get("ANKKA_URL").orElse(config.get("url")).getOrElse("http://localhost:9000")
    val bearer = env.get("ANKKA_TOKEN").orElse(config.get("token")) match
      case Some(t) => Bearer.Given(Some(t))
      case None    => Bearer.Login(dir.resolve("credentials.json"), url, refresh)
    (url, bearer, env.get("ANKKA_CA").orElse(config.get("ca")))

  /** The four string fields of the CLI's config file; nothing else is read from it. */
  private def ujsonLite(text: String): Map[String, String] =
    val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
    Vector("url", "token", "project", "ca").flatMap(f => Option(node.get(f)).filter(_.isTextual).map(f -> _.asText())).toMap

  /** The platform's roots plus one PEM authority, as the CLI trusts `ANKKA_CA`. */
  def trusting(pemPath: String): SSLContext =
    val certificates = CertificateFactory.getInstance("X.509")
    val store = KeyStore.getInstance(KeyStore.getDefaultType)
    store.load(null, null)
    val in = FileInputStream(pemPath)
    try certificates.generateCertificates(in).toArray.zipWithIndex.foreach { (c, i) =>
      store.setCertificateEntry(s"ankka-ca-$i", c.asInstanceOf[java.security.cert.Certificate])
    } finally in.close()
    val defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    defaults.init(null.asInstanceOf[KeyStore])
    val extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    extra.init(store)
    val context = SSLContext.getInstance("TLS")
    context.init(null, extra.getTrustManagers ++ defaults.getTrustManagers, null)
    context

/** The 040 grant shapes, written by hand: they are not in ankka-controlplane-api. The tool keeps a target
  * as one string (`route:<service> <METHOD> <path>`, `topic:<name>:<right>`, `erasure`, see
  * [[Render.target]]); the control plane (ankka 0.12, `POST /projects/{id}/grants`) takes and answers it
  * as an object with a `kind`, so the two forms are translated here, both ways.
  */
object GrantJson:
  private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
  private val Route  = "route:([^ ]+) ([A-Z]+) (.+)".r
  private val Topic  = "topic:([^:]+):([a-z]+)".r

  def request(grant: GrantRequest): String =
    val target = mapper.createObjectNode()
    grant.target match
      case Route(service, method, path) =>
        target.put("kind", "route").put("service", service).put("method", method).put("path", path)
      case Topic(name, right) =>
        target.put("kind", "topic").put("topic", name).put("right", right)
      case "erasure" => target.put("kind", "erasure")
      case other     => throw IllegalArgumentException(s"not a grant target: $other")
    val body = mapper.createObjectNode()
    body.put("grantee", grant.principal)
    body.set("target", target)
    mapper.writeValueAsString(body)

  /** The control plane's target object, back in the tool's string form (unknown kinds verbatim). */
  def target(node: com.fasterxml.jackson.databind.JsonNode): String =
    def text(field: String) = Option(node.get(field)).map(_.asText()).getOrElse("")
    if node == null then ""
    else if node.isTextual then node.asText()
    else
      text("kind") match
        case "route"   => s"route:${text("service")} ${text("method")} ${text("path")}"
        case "topic"   => s"topic:${text("topic")}:${text("right")}"
        case "erasure" => "erasure"
        case _         => node.toString

  def statuses(text: String): Vector[GrantStatus] =
    import scala.jdk.CollectionConverters.*
    mapper.readTree(text).elements().asScala.toVector.map { g =>
      GrantStatus(
        Option(g.get("grantee")).map(_.asText()).getOrElse(""),
        target(g.get("target")),
        Option(g.get("effect")).orElse(Option(g.get("state"))).map(_.asText()).getOrElse("unknown")
      )
    }
