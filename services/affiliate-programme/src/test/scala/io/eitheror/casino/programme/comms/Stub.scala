package io.eitheror.casino.programme.comms

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import scala.collection.mutable

/** A local HTTP server answering by method and path, keeping what it was sent. */
final class Stub(answer: (String, String, String) => (Int, String, Map[String, String])):
  final case class Seen(method: String, path: String, headers: Map[String, String], body: String)
  val seen = mutable.ArrayBuffer.empty[Seen]

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext("/", exchange => {
    val body = String(exchange.getRequestBody.readAllBytes(), "UTF-8")
    val uri  = exchange.getRequestURI
    val path = uri.getPath + Option(uri.getRawQuery).fold("")("?" + _)
    val headers = Map("authorization" -> Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse(""))
    synchronized(seen += Seen(exchange.getRequestMethod, path, headers, body))
    val (status, out, extra) = answer(exchange.getRequestMethod, path, body)
    extra.foreach((k, v) => exchange.getResponseHeaders.add(k, v))
    val bytes = out.getBytes("UTF-8")
    exchange.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
    if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
    exchange.close()
  })
  server.start()

  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"
  def stop(): Unit = server.stop(0)
