package io.eitheror.casino.support.it

import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/** One frame of a stream: its event name and its JSON data. */
final case class Frame(name: String, data: String):
  /** A field's text as sent: the helpers' `text` trims, and a token's leading space is the model's. */
  def field(f: String): String = Json.parse(data).flatMap(n => Option(n.get(f))).filterNot(_.isNull).map(_.asText).getOrElse("")

/**
 * A `POST` answered as `text/event-stream`, read on a background thread as engagement's reader: each frame's
 * `event:` name and its `data:` lines joined. `status` waits for the response to start; `body` is the whole answer,
 * for a refusal made before the stream opened. `firstFrameMillis` is how long the first frame took (SC-002).
 */
final class SseReader(url: String, sent: String, headers: Seq[(String, String)]):
  private val started = CountDownLatch(1)
  private val ended   = CountDownLatch(1)
  private val sentAt  = System.nanoTime
  @volatile private var code  = 0
  @volatile private var seen  = Vector.empty[Frame]
  @volatile private var lines = Vector.empty[String]
  @volatile private var first = Option.empty[Long]

  private val client = HttpClient.newBuilder.version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build()
  Thread.ofVirtual.start(() => run()): Unit

  private def run(): Unit =
    try
      val b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(sent)).header("Content-Type", "application/json")
      headers.foreach((k, v) => b.header(k, v))
      val response = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
      code = response.statusCode
      started.countDown()
      val in   = BufferedReader(InputStreamReader(response.body, UTF_8))
      var name = Option.empty[String]
      var data = Vector.empty[String]
      var line = in.readLine()
      while line != null do
        lines = lines :+ line
        if line.isEmpty then
          if name.isDefined || data.nonEmpty then
            if first.isEmpty then first = Some((System.nanoTime - sentAt) / 1_000_000L)
            seen = seen :+ Frame(name.getOrElse("message"), data.mkString("\n"))
          name = None
          data = Vector.empty
        else if line.startsWith(":") then ()
        else if line.startsWith("event:") then name = Some(line.stripPrefix("event:").trim)
        else if line.startsWith("data:") then data = data :+ line.stripPrefix("data:").stripPrefix(" ")
        line = in.readLine()
    catch case _: Exception => ()
    finally
      started.countDown()
      ended.countDown()

  def status: Int =
    started.await(10, TimeUnit.SECONDS): Unit
    code

  /** Every frame, once the stream has ended (within `within`). */
  def frames(within: FiniteDuration = 20.seconds): Vector[Frame] =
    if !ended.await(within.toMillis, TimeUnit.MILLISECONDS) then throw AssertionError(s"the stream did not end within $within; frames: $seen")
    seen

  def names(within: FiniteDuration = 20.seconds): Vector[String] = frames(within).map(_.name)

  /** The text of the `delta`s, joined. */
  def text(within: FiniteDuration = 20.seconds): String = frames(within).filter(_.name == "delta").map(_.field("token")).mkString

  def body: String = { ended.await(20, TimeUnit.SECONDS): Unit; lines.mkString("\n") }

  def refusal: String = Json.parse(body).flatMap(_.text("error")).getOrElse("")

  def firstFrameMillis: Option[Long] = { ended.await(20, TimeUnit.SECONDS): Unit; first }
