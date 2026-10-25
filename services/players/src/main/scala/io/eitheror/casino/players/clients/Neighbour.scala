package io.eitheror.casino.players.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.players.Json

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

/** A neighbour's answer: its status and body, whatever the status. */
final case class Reply(status: Int, body: Option[JsonNode], text: String):
  def ok: Boolean = status / 100 == 2

/** Where a call goes: a service of this project, or of another (`payments`' `merchant`), and its prefix. */
final case class Target(name: String, prefix: String, project: Option[String] = None):
  def describe: String = project.fold(name)(p => s"$p/$name")

/** An answer the caller could not read: the call counts as failed, never as a decision. */
final case class Unreadable(service: String, why: String) extends RuntimeException(s"$service answered what cannot be read: $why")

/**
 * Calls to the project's services as this service, with no token (spec 001 FR-020, service class),
 * each on a virtual thread of its own so the caller can bound it with [[Bound]] and still hear it late.
 * A call that got no answer (`ServiceUnresolvable`, `ServiceUnanswered`) is a failed future. Inside
 * `inline` the call is made on the caller's own thread instead: a workflow step's service client
 * answers only there (0.13's step scope is a thread-local), and the step's timeout is its bound.
 */
object Neighbour:
  given calls: ExecutionContext = ExecutionContext.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())

  def segment(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  private val inlined = ThreadLocal.withInitial[java.lang.Boolean](() => false)

  /** Runs `body` with every call in it made on this thread (a workflow step). */
  def inline[A](body: => A): A =
    val was = inlined.get
    inlined.set(true)
    try body
    finally inlined.set(was)

  def call(services: ServiceClients, target: Target, method: String, path: String, body: Option[JsonNode] = None): Future[Reply] =
    def run(): Reply =
      val client = target.project.fold(services(target.name))(services(_, target.name))
      val r = client.request(method, target.prefix + path, body.map(b => Json.render(b).getBytes(UTF_8)), body.map(_ => "application/json"))
      Reply(r.status, Json.parse(r.text).filter(_ => r.text.trim.nonEmpty), r.text)
    if inlined.get then Future.fromTry(scala.util.Try(run())) else Future(run())

  /** The call raced against `bound`; an answer `read` refuses is a failure naming the service. */
  def bounded[A](service: String, bound: Duration)(call: => Future[Reply])(read: Reply => Either[String, A]): Raced[A] =
    Bound.within(bound)(call.flatMap(r => read(r).fold(why => Future.failed(Unreadable(service, why)), Future.successful)))
