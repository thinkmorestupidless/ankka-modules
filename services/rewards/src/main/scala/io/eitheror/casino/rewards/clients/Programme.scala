package io.eitheror.casino.rewards.clients

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.rewards.{Json, Settings}
import io.eitheror.casino.rewards.Json.*

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpConnectTimeoutException, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import scala.util.{Try, Using}
import scala.util.control.NonFatal

/** One affiliate bonus code: its text, the brand offer it names, and its signed tag (spec 012 contracts/protocol.md). */
final case class ProgrammeCode(code: String, offerId: String, tag: String)

/** The programme's code document for the brand (spec 013 FR-023a): its version and time, for staleness (R13). */
final case class ProgrammeDocument(brand: String, version: Long, at: Option[Instant], codes: Vector[ProgrammeCode])

object ProgrammeDocument:
  /** What a 404 means: the programme publishes no codes for the brand. */
  val none: ProgrammeDocument = ProgrammeDocument("", 0L, None, Vector.empty)

/** The programme's public code document, fetched as a public client with no credential (research R13). */
trait Programme:
  def fetch(url: String): Called[ProgrammeDocument]

object Programme:
  /** A document is at most this long; a longer one is not read. */
  val MaxBytes: Int = 1024 * 1024

  def http(settings: Settings): Programme = Http(settings.requestBound)

  def document(b: JsonNode): Option[ProgrammeDocument] =
    Option(b.get("codes")).filter(_.isArray).map { _ =>
      ProgrammeDocument(b.textOr("brand", ""), b.long("version").getOrElse(0L), b.text("at").flatMap(t => Try(Instant.parse(t)).toOption),
        b.objects("codes").flatMap(c => for code <- c.text("code"); offer <- c.text("offer") yield ProgrammeCode(code, offer, c.textOr("tag", ""))))
    }

  /**
   * Over `java.net.http.HttpClient` (ankka's service clients reach services by name, not an absolute address), as
   * attribution's `DocumentFetch`: 5 s to connect, `bound` per request, no redirects, `http` or `https`, at most
   * 1 MiB read. The URL is never logged nor answered back.
   */
  final class Http(bound: Duration) extends Programme:
    private val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

    def fetch(url: String): Called[ProgrammeDocument] =
      Try(URI.create(url)).toOption.filter(u => u.getScheme == "https" || u.getScheme == "http") match
        case None => Called.Unreachable("the code document's address is not http or https")
        case Some(u) =>
          get(u) match
            // No document published for the brand: no codes, kept like any document (research R13).
            case Reply.Answered(404, _, _) => Called.Done(ProgrammeDocument.none)
            case reply                     => Called.of(reply, "the programme")(document)

    private def get(u: URI): Reply =
      try
        val request  = HttpRequest.newBuilder(u).timeout(bound).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        Using(response.body)(read).toEither match
          case Left(e)           => Reply.Unreachable(s"the programme's answer could not be read: ${e.getClass.getSimpleName}")
          case Right(None)       => Reply.Unreachable("the code document is over 1 MiB")
          case Right(Some(text)) => Reply.Answered(response.statusCode, Json.parse(text).filter(_ => text.trim.nonEmpty), text)
      catch
        case _: HttpConnectTimeoutException => Reply.Unreachable("the programme: connect timed out")
        case _: HttpTimeoutException        => Reply.Unreachable("the programme: timed out")
        case _: InterruptedException        => Thread.currentThread.interrupt(); Reply.Unreachable("interrupted")
        case NonFatal(e)                    => Reply.Unreachable(s"the programme unreachable: ${e.getClass.getSimpleName}")

    private def read(in: InputStream): Option[String] =
      val bytes = in.readNBytes(MaxBytes + 1)
      Option.when(bytes.length <= MaxBytes)(String(bytes, UTF_8))
