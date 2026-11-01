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
    val AccountNotFound  = "account_not_found"
    val NotAMember       = "not_a_member"
    val BrandNotHeld     = "brand_not_held"
    val BrandUnknown     = "brand_unknown"
    val StatusTransition = "status_transition"
    val PayoutPaid       = "payout_paid"
    val Erased           = "erased"
    val NotBuilt         = "not_built"

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
      case ErrorCode.Conflict => 409
      case ErrorCode.NotFound => 404
      case _                  => 400
    if code.matches("[a-z_]+") then refuse(status, code, text.drop(code.length).stripPrefix(":").trim)
    else refuse(status, text)

  /** A route declared and not yet built: 501 naming it. */
  def notBuilt(route: String): Respond[Bytes] = refuse(501, Codes.NotBuilt, route)

  def answer[A](status: Int, a: A)(using c: JsonValueCodec[A]): Respond[Bytes] =
    Respond(Bytes("application/json", writeToString(a).getBytes(UTF_8)), status)

  def ok[A: JsonValueCodec](a: A): Respond[Bytes] = answer(200, a)

  def raw(status: Int, contentType: String, body: String): Respond[Bytes] = Respond(Bytes(contentType, body.getBytes(UTF_8)), status)

  def noContent: Respond[Bytes] = Respond(Bytes("application/json", Array.emptyByteArray), 204)

  def read[A](body: String)(using c: JsonValueCodec[A]): Either[String, A] =
    Try(readFromString[A](body)).toEither.left.map(e => Option(e.getMessage).getOrElse("the body is not the route's shape"))
