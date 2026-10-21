package io.eitheror.casino.pspgateway

import com.thinkmorestupidless.ankka.sdk.{ServiceClient, ServiceClients, ServiceResponse, ServiceUnanswered}

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/**
 * Service clients answering from a script, recording every call: for the seams' suites, which test
 * what is asked and how each answer is read without a running neighbour.
 */
final class Scripted(answer: Scripted.Call => Option[(Int, String)]) extends ServiceClients:
  val calls: mutable.Buffer[Scripted.Call] = mutable.Buffer.empty

  def apply(name: String): ServiceClient = client("payments", name)
  def apply(project: String, name: String): ServiceClient = client(project, name)

  private def client(project: String, name: String): ServiceClient = new ServiceClient:
    def target: String = s"$project/$name"
    def request(method: String, path: String, body: Option[Array[Byte]], contentType: Option[String],
        headers: Seq[(String, String)]): ServiceResponse =
      val call = Scripted.Call(project, name, method, path, body.map(String(_, UTF_8)), headers.toVector)
      calls += call
      answer(call) match
        case Some((status, text)) => ServiceResponse(status, "application/json", text.getBytes(UTF_8), Vector.empty)
        case None                 => throw ServiceUnanswered(target, "silent")

object Scripted:
  final case class Call(project: String, name: String, method: String, path: String, body: Option[String],
      headers: Vector[(String, String)])
