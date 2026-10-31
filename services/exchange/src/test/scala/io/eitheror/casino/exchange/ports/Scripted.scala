package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.ankka.sdk.{ServiceClient, ServiceClients, ServiceResponse, ServiceUnanswered}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Service clients that answer from a script, for the ports' unit suites: `answers` maps a service
 * and a path to (status, body), or to none for a call that is never answered. Every call is logged
 * as (service, method, path, body).
 */
final class Scripted(answers: (String, String) => Option[(Int, String)]) extends ServiceClients:
  val log = ConcurrentLinkedQueue[(String, String, String, String)]()

  def apply(name: String): ServiceClient = new ServiceClient:
    def target = s"local/$name"
    def request(method: String, path: String, body: Option[Array[Byte]], contentType: Option[String], headers: Seq[(String, String)]) =
      log.add((name, method, path, body.fold("")(String(_, UTF_8))))
      answers(name, path) match
        case Some((status, text)) => ServiceResponse(status, "application/json", text.getBytes(UTF_8), Vector.empty)
        case None                 => throw ServiceUnanswered(name, "scripted: no answer")

  def apply(project: String, name: String): ServiceClient = apply(name)

  def calls: Vector[(String, String, String, String)] = log.asScala.toVector
