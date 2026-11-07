package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/**
 * How the routes answer: JSON bodies, and refusals as the original writes them, `{"error": "<code>"}`
 * (`S/AffiliateRoutes.scala:312`), with a message when there is more to say (contracts/routes.md).
 */
object Wire:
  final case class Refusal(error: String, message: Option[String] = None)
  given JsonValueCodec[Refusal] = ProgrammeCodecs.wire[Refusal]

  object Codes:
    val InvalidRequest   = "invalid_request"
    val InvalidPeriod    = "invalid_period"
    val PlanNotFound     = "plan_not_found"
    val NoPlanAssigned   = "no_plan_assigned"
    val ParentNotFound   = "parent_not_found"
    val ParentCycle      = "parent_cycle"
    val EmailRegistered  = "email_already_registered"
    val AccountNotFound  = "account_not_found"
    val NotAMember       = "not_a_member"
    val BrandNotHeld     = "brand_not_held"
    val BrandUnknown     = "brand_unknown"
    val StatusTransition = "status_transition"
    val PayoutPaid       = "payout_paid"
    val Erased           = "erased"
    val NotBuilt         = "not_built"
    /** The original's ACL refusal, its text as Akka HTTP wrote it. */
    val NotAuthorised    = "The supplied authentication is not authorized to access this resource"

  def refuse(status: Int, code: String, message: String = ""): Respond[Bytes] =
    answer(status, Refusal(code, Option(message).filter(_.nonEmpty)))

  def invalid(message: String): Respond[Bytes] = refuse(400, Codes.InvalidRequest, message)

  /**
   * A component's refusal as the route's: the component's message starts with the code
   * (`email_already_registered`, `must be pending, currently active`), which is the original's `error`.
   */
  def refused(e: CommandError): Respond[Bytes] =
    val text = Option(e.getMessage).getOrElse("")
    val code = text.takeWhile(c => c != ':').trim
    val status = e.code match
      case _ if code == "too_many_requests" => 429
      case ErrorCode.Conflict  => 409
      case ErrorCode.NotFound  => 404
      case ErrorCode.Forbidden => 403
      case _                   => 400
    if code.matches("[a-z_]+") then refuse(status, code, text.drop(code.length).stripPrefix(":").trim)
    else refuse(status, text)

  /** The route's answer, or a component's refusal as the original's. */
  def attempt(f: => Respond[Bytes]): Respond[Bytes] =
    try f
    catch case e: CommandError => refused(e)

  /** A body read as the route's shape, or `400 invalid_request` naming what was wrong. */
  def withBody[A: JsonValueCodec](body: String)(f: A => Respond[Bytes]): Respond[Bytes] =
    read[A](if body.trim.isEmpty then "{}" else body).fold(invalid, f)

  /** A route declared and not yet built: 501 naming it. */
  def notBuilt(route: String): Respond[Bytes] = refuse(501, Codes.NotBuilt, route)

  def answer[A](status: Int, a: A)(using c: JsonValueCodec[A]): Respond[Bytes] =
    Respond(Bytes("application/json", writeToString(a).getBytes(UTF_8)), status)

  def ok[A: JsonValueCodec](a: A): Respond[Bytes] = answer(200, a)

  def raw(status: Int, contentType: String, body: String): Respond[Bytes] = Respond(Bytes(contentType, body.getBytes(UTF_8)), status)

  def noContent: Respond[Bytes] = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def read[A](body: String)(using c: JsonValueCodec[A]): Either[String, A] =
    Try(readFromString[A](body)).toEither.left.map(e => Option(e.getMessage).getOrElse("the body is not the route's shape"))
