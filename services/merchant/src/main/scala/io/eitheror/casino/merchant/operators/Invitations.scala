package io.eitheror.casino.merchant.operators

import io.eitheror.casino.merchant.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.Try

/** What an invitation carries: to whom, the operator, and the link with the token. */
final case class Invitation(kind: Invitation.Kind, operatorId: String, email: String, token: String, link: String)

object Invitation:
  enum Kind:
    case Invite, Resend, PasswordReset

/**
 * How an operator is told of their invitation (FR-027, research R10): the provider the installation
 * configures on this service, since a shared project holds no grant into a brand's `engagement`
 * (planning 2026-10-12). Customer.io's transactional email as the original sends it; the suites record.
 */
trait Invitations:
  def send(invitation: Invitation): Either[String, Unit]

object Invitations:
  /** The links the original sends (`OperatorInviteEmailService`): the accept page, or the reset page for a reset. */
  def link(consoleUrl: String, kind: Invitation.Kind, token: String): String =
    val page = if kind == Invitation.Kind.PasswordReset then "reset-password" else "accept-invitation"
    s"${consoleUrl.stripSuffix("/")}/$page?token=$token"

  /** Customer.io's settings, from the secret store entry `engagement/transactional`. */
  final case class CustomerIo(appApiKey: String, transactionalId: String, passwordResetTransactionalId: Option[String])

  def customerIo(region: String, settings: () => Option[CustomerIo], baseOverride: Option[String] = None): Invitations =
    new Invitations:
      private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
      private val base = baseOverride.getOrElse(if region == "eu" then "https://api-eu.customer.io" else "https://api.customer.io")
      def send(i: Invitation): Either[String, Unit] =
        settings() match
          case None => Left("no transactional email configured at engagement/transactional")
          case Some(c) =>
            val tid = if i.kind == Invitation.Kind.PasswordReset then c.passwordResetTransactionalId.getOrElse(c.transactionalId) else c.transactionalId
            val body = Json.obj("transactional_message_id" -> tid, "to" -> i.email, "identifiers" -> Json.obj("email" -> i.email),
              "message_data" -> Json.obj("invite_url" -> i.link, "operator_id" -> i.operatorId))
            Try(http.send(HttpRequest.newBuilder(URI.create(s"$base/v1/send/email")).header("Authorization", s"Bearer ${c.appApiKey}")
              .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.render(body))).build(),
              HttpResponse.BodyHandlers.ofString())).toEither.left.map(_.getMessage)
              .flatMap(r => if r.statusCode / 100 == 2 then Right(()) else Left(s"customer.io answered ${r.statusCode}"))
