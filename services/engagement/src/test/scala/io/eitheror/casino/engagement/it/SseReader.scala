package io.eitheror.casino.engagement.it

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A `text/event-stream` read on a background thread: each frame's `event:` name (`message` when
 * none) and its `data:` lines joined, comments and empty keep-alives ignored, a frame cut off by
 * the end dropped. `status` waits for the response to start; `body` is everything read, for an
 * answer that is not a stream (a refusal).
 */
final class SseReader(url: String, headers: Map[String, String] = Map.empty):
  private val started = CountDownLatch(1)
  private val ended   = CountDownLatch(1)
  @volatile private var code   = 0
  @volatile private var stream = Option.empty[InputStream]
  @volatile private var seen   = Vector.empty[(String, String)]
  @volatile private var lines  = Vector.empty[String]
  private val taken = scala.collection.mutable.Map.empty[String, Int]

  private val client = HttpClient.newBuilder.connectTimeout(java.time.Duration.ofSeconds(5)).build()

  private val reader = Thread.ofVirtual.start(() => run())

  private def run(): Unit =
    try
      val request  = headers.foldLeft(HttpRequest.newBuilder(URI.create(url)).GET())((b, h) => b.header(h._1, h._2)).build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
      code = response.statusCode
      stream = Some(response.body)
      started.countDown()
      val in   = BufferedReader(InputStreamReader(response.body, UTF_8))
      var name = Option.empty[String]
      var data = Vector.empty[String]
      var line = in.readLine()
      while line != null do
        lines = lines :+ line
        if line.isEmpty then
          if name.isDefined || data.nonEmpty then publish(name.getOrElse("message"), data.mkString("\n"))
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
      synchronized(notifyAll())

  private def publish(name: String, data: String): Unit = synchronized {
    seen = seen :+ (name -> data)
    notifyAll()
  }

  def frames: Vector[(String, String)] = seen

  /** The next frame named `name` not yet returned by `next`, waiting for it up to `within`. */
  def next(name: String, within: FiniteDuration = 5.seconds): (String, String) = synchronized {
    val deadline = System.nanoTime + within.toNanos
    def found    = seen.filter(_._1 == name).drop(taken.getOrElse(name, 0)).headOption
    while found.isEmpty && !closed && System.nanoTime < deadline do wait(math.max(1L, (deadline - System.nanoTime) / 1_000_000L))
    found match
      case Some(frame) =>
        taken.update(name, taken.getOrElse(name, 0) + 1)
        frame
      case None =>
        throw AssertionError(s"no `$name` frame within $within${if closed then " (the stream ended)" else ""}; frames: $seen")
  }

  /** The response's status, once it starts (0 when it never did within 10 s). */
  def status: Int =
    started.await(10, TimeUnit.SECONDS): Unit
    code

  def body: String = lines.mkString("\n")

  def closed: Boolean = ended.getCount == 0

  /** Waits up to `within` for the stream to end; whether it did. */
  def awaitClosed(within: FiniteDuration = 5.seconds): Boolean = ended.await(within.toMillis, TimeUnit.MILLISECONDS)

  def close(): Unit =
    stream.foreach(s => Try(s.close()))
    reader.interrupt()
    client.shutdownNow()
