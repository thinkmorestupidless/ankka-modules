package io.eitheror.casino.gameprovider.it

import com.thinkmorestupidless.ankka.sdk.{ServiceClient, ServiceClients, ServiceResponse}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.jdk.CollectionConverters.*

/** ankka's service clients pointed straight at the fakes, for a client's unit test with no runtime. */
final class DirectServices(addresses: Map[String, String]) extends ServiceClients:
  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def apply(name: String): ServiceClient = client(addresses.getOrElse(name, throw IllegalStateException(s"no address for $name")))
  def apply(project: String, name: String): ServiceClient = apply(name)

  private def client(base: String): ServiceClient = new ServiceClient:
    def target: String = base
    def request(method: String, path: String, body: Option[Array[Byte]], contentType: Option[String], headers: Seq[(String, String)]): ServiceResponse =
      val b = HttpRequest.newBuilder(URI.create(base + path))
        .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofByteArray))
      contentType.foreach(ct => b.header("Content-Type", ct))
      headers.foreach((k, v) => b.header(k, v))
      val r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
      ServiceResponse(r.statusCode, r.headers.firstValue("content-type").orElse(""), r.body,
        r.headers.map.asScala.toVector.flatMap((k, vs) => vs.asScala.map(k -> _)))
