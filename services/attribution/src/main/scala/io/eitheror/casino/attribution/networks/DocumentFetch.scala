package io.eitheror.casino.attribution.networks

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpConnectTimeoutException, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.Duration
import scala.util.Using
import scala.util.control.NonFatal

/**
 * A network's public document, fetched as a public client with no credential (research R3). The seam the
 * kit replaces with `FakeNetwork`'s answers.
 */
trait DocumentFetch:
  /** The status and the body, or why nothing was answered. */
  def fetch(url: String): Either[String, (Int, String)]

object DocumentFetch:

  /**
   * Over `java.net.http.HttpClient`, as `psp-gateway`'s `ProviderHttp` calls a provider: a 5 s connect
   * bound, `DOCUMENT_TIMEOUT` per request, no redirects, `https` or `http`, the body read to at most 1 MiB.
   */
  def http(timeout: Duration): DocumentFetch = new DocumentFetch:
    private val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

    def fetch(url: String): Either[String, (Int, String)] =
      val uri = scala.util.Try(URI.create(url)).toOption.filter(u => u.getScheme == "https" || u.getScheme == "http")
      uri match
        case None => Left(s"'$url' is not an http or https address")
        case Some(u) =>
          try
            val request  = HttpRequest.newBuilder(u).timeout(timeout).header("Accept", "application/json").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            Using(response.body)(read).toEither.left.map(_.getMessage).flatMap {
              case None       => Left("the document is over 1 MiB")
              case Some(body) => Right(response.statusCode -> body)
            }
          catch
            case _: HttpConnectTimeoutException => Left("connect timed out")
            case _: HttpTimeoutException        => Left("timed out")
            case _: InterruptedException        => Thread.currentThread.interrupt(); Left("interrupted")
            case NonFatal(e)                    => Left(e.getClass.getSimpleName)

  /** The body as text, or none when it is longer than a document may be. */
  private def read(in: InputStream): Option[String] =
    val bytes = in.readNBytes(Documents.MaxBytes + 1)
    Option.when(bytes.length <= Documents.MaxBytes)(String(bytes, "UTF-8"))
