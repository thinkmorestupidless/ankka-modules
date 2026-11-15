package io.eitheror.casino.backoffice

import java.net.URI
import scala.util.Try

/**
 * What the service is told at start (`modules/backoffice/module.json`): the brand, the operators' realm it
 * signs them in at (`ANKKA_AUTH_STAFF_ISSUER`), the brand's own client there, the backoffice's address
 * (`BACKOFFICE_HOST`: the code flow's redirect goes back to it), and the client's secret
 * (`BACKOFFICE_CLIENT_SECRET`, a `secretKeyRef` to the project's `tenant-values`). The secret is kept out
 * of `toString`.
 */
final case class Settings(brand: String, realm: URI, client: String, address: URI, clientSecret: String = ""):
  override def toString: String = s"Settings($brand, $realm, $client, $address, secret ${if clientSecret.isEmpty then "unset" else "set"})"

  /** The realm's token, revocation and end-session addresses, by Keycloak's layout. */
  def tokenUrl: URI      = URI.create(s"$realm/protocol/openid-connect/token")
  def revokeUrl: URI     = URI.create(s"$realm/protocol/openid-connect/revoke")
  def authorizeUrl: URI  = URI.create(s"$realm/protocol/openid-connect/auth")
  def callback: URI      = URI.create(s"$address/auth/callback")

object Settings:

  /** The client's secret's configuration key (amendment D3; `<slug>-backoffice/CLIENT_SECRET` in the secrets file). */
  val ClientSecret = "backoffice-client-secret"

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    for
      brand   <- required(env, "CASINO_BRAND")
      realm   <- required(env, "ANKKA_AUTH_STAFF_ISSUER").flatMap(uri("ANKKA_AUTH_STAFF_ISSUER", _))
      address <- required(env, "BACKOFFICE_HOST").flatMap(uri("BACKOFFICE_HOST", _))
      client   = env.get("BACKOFFICE_CLIENT").map(_.trim).filter(_.nonEmpty).getOrElse(s"backoffice-$brand")
      secret   = env.get("BACKOFFICE_CLIENT_SECRET").map(_.trim).getOrElse("")
    yield Settings(brand, realm, client, address, secret)

  private def required(env: Map[String, String], name: String): Either[String, String] =
    env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$name is not set: the service is deployed by the modules tool")

  private def uri(name: String, v: String): Either[String, URI] =
    Try(URI.create(v.stripSuffix("/"))).toOption.filter(u => u.getScheme == "https" || u.getScheme == "http")
      .toRight(s"$name: '$v' is not an http or https address")
