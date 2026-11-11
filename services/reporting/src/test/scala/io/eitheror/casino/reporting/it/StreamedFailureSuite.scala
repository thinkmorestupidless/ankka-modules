package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import io.eitheror.casino.reporting.api.Wire

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A streamed answer whose rows fail partway (the ledger's JSON export, `Reports.ledgerJson`, reading a view a page at a
 * time): the status and the first pages are on the wire already, so ankka 0.16, which writes a `Streamed` body of no
 * length as a chunked entity read from the stream, aborts the connection without the last chunk. The client reads an
 * error, not a complete body that parses. Through `Wire.streamed` and `Wire.concatenated`, as the route answers it, on
 * the shared kit's server (one service per test JVM: a second kit would take the first's keyring with it).
 */
class StreamedFailureSuite extends munit.FunSuite:
  override val munitTimeout = 3.minutes
  private lazy val kit = SharedKit.kit
  private val http     = HttpClient.newHttpClient()

  private def fetch(path: String) =
    http.send(HttpRequest.newBuilder(URI.create(kit.base + path)).timeout(java.time.Duration.ofSeconds(30)).build(),
      HttpResponse.BodyHandlers.ofInputStream())

  test("a stream that ends is the whole body") {
    val r = fetch("/test-stream/whole")
    assertEquals((r.statusCode, String(r.body.readAllBytes(), UTF_8)), (200, """{"transactions":[1]}"""))
  }

  test("a stream that fails partway is a 200 whose body the client cannot finish reading: the connection is aborted") {
    val r = fetch("/test-stream/failing")
    assertEquals(r.statusCode, 200)
    val read = Try(r.body.readAllBytes())
    assert(read.failed.toOption.exists(_.isInstanceOf[java.io.IOException]),
      s"the body was read whole: ${read.map(b => String(b, UTF_8).takeRight(80))}")
  }

object StreamedFailureSuite:
  /** More than one chunk of rows before the failure, so the response is well under way when it fails. */
  private def rows = Iterator.tabulate(2_000)(n => s"""{"transactionId":"t-$n","amount":"1.00"},""".getBytes(UTF_8))

  private final class Streams extends HttpEndpoint("/test-stream"):
    val acl: Acl = Acl.AllowAll
    get("/whole") { () => Wire.streamed("application/json", Wire.concatenated(Iterator("""{"transactions":[1]}""".getBytes(UTF_8)))) }
    get("/failing") { () =>
      val failing = Iterator.single(0).map[Array[Byte]](_ => throw IllegalStateException("the view failed partway"))
      Wire.streamed("application/json", Wire.concatenated(Iterator("""{"transactions":[""".getBytes(UTF_8)) ++ rows ++ failing))
    }

  /** The kit serves it beside the service's own endpoint. */
  val endpoint: EndpointClients => HttpEndpoint = _ => Streams()
