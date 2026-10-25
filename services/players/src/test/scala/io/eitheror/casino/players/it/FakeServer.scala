package io.eitheror.casino.players.it

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.eitheror.casino.players.Json

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import scala.collection.mutable

/** One request a fake received. */
final case class Received(method: String, path: String, query: Map[String, String], body: String, headers: Map[String, String],
    rawQuery: String = ""):
  def json: Option[JsonNode] = Json.parse(body)

/** A response a fake sends. */
final case class Answer(status: Int, body: String, contentType: String = "application/json", headers: Map[String, String] = Map.empty)

object Answer:
  def json(status: Int, node: JsonNode): Answer = Answer(status, Json.render(node))
  def ok(node: JsonNode): Answer              = json(200, node)

/**
 * An in-process HTTP server for the fakes, on a free loopback port unless one is given (copied from psp-gateway's,
 * with a stop and a start on the same port, for "merchant is unreachable"). Each fake routes on method and path;
 * everything received is kept, in order, so a test can assert what was (and was not) asked.
 */
abstract class FakeServer(bind: Int = 0):
  private val log = mutable.ArrayBuffer.empty[Received]
  @volatile private var server = open(bind)

  val port: Int       = server.getAddress.getPort
  val address: String = s"http://127.0.0.1:$port"

  private def open(at: Int): HttpServer =
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", at), 0)
    s.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    s.createContext("/", (ex: HttpExchange) => handle(ex))
    s.start()
    s

  protected def route(r: Received): Answer

  def received: Vector[Received] = log.synchronized(log.toVector)
  def clearReceived(): Unit       = log.synchronized(log.clear())
  def stop(): Unit                = server.stop(0)
  /** Serves again on the same port after a stop. */
  def start(): Unit               = server = open(port)

  private def handle(ex: HttpExchange): Unit =
    try
      val body    = String(ex.getRequestBody.readAllBytes(), UTF_8)
      val rawPath = ex.getRequestURI.getRawPath
      val path    = rawPath.split('/').map(s => URLDecoder.decode(s, UTF_8)).mkString("/")
      val query = Option(ex.getRequestURI.getRawQuery).toVector.flatMap(_.split('&')).flatMap { kv =>
        kv.split("=", 2) match
          case Array(k, v) => Some(URLDecoder.decode(k, UTF_8) -> URLDecoder.decode(v, UTF_8))
          case Array(k)    => Some(URLDecoder.decode(k, UTF_8) -> "")
          case _           => None
      }.toMap
      val headers = ex.getRequestHeaders.entrySet.toArray.toVector.collect {
        case e: java.util.Map.Entry[?, ?] => e.getKey.toString.toLowerCase -> e.getValue.asInstanceOf[java.util.List[String]].get(0)
      }.toMap
      val raw = Option(ex.getRequestURI.getRawQuery).map(q => URLDecoder.decode(q, UTF_8)).getOrElse("")
      val r   = Received(ex.getRequestMethod, path, query, body, headers, raw)
      log.synchronized(log += r)
      val a     = route(r)
      val bytes = a.body.getBytes(UTF_8)
      ex.getResponseHeaders.add("Content-Type", a.contentType)
      a.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
      ex.sendResponseHeaders(a.status, if bytes.isEmpty then -1 else bytes.length)
      if bytes.nonEmpty then ex.getResponseBody.write(bytes)
    catch
      case e: Throwable =>
        val bytes = s"""{"error":"FAKE_FAILED","message":"${e.toString.replace('"', '\'')}"}""".getBytes(UTF_8)
        ex.sendResponseHeaders(500, bytes.length)
        ex.getResponseBody.write(bytes)
    finally ex.close()
