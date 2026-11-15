package io.eitheror.casino.backoffice

import java.net.URI
import scala.util.Try

/**
 * What the service is told at start: the brand, the operators' realm it signs them in at, the brand's
 * own client there, and the backoffice's address (the code flow's redirect goes back to it). No secret is
 * a setting: the client's secret is in the store, under [[Settings.ClientSecret]].
 */
final case class Settings(brand: String, realm: URI, client: String, address: URI):
  /** The realm's token, revocation and end-session addresses, by Keycloak's layout. */
  def tokenUrl: URI      = URI.create(s"$realm/protocol/openid-connect/token")
  def revokeUrl: URI     = URI.create(s"$realm/protocol/openid-connect/revoke")
  def authorizeUrl: URI  = URI.create(s"$realm/protocol/openid-connect/auth")
  def callback: URI      = URI.create(s"$address/auth/callback")

object Settings:

  /** The client's secret, written to the store by the modules tool with the brand (amendment D3). */
  val ClientSecret = "backoffice-client-secret"

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    for
      brand   <- required(env, "CASINO_BRAND")
      realm   <- required(env, "BACKOFFICE_REALM").flatMap(uri("BACKOFFICE_REALM", _))
      address <- required(env, "BACKOFFICE_ADDRESS").flatMap(uri("BACKOFFICE_ADDRESS", _))
      client   = env.get("BACKOFFICE_CLIENT").map(_.trim).filter(_.nonEmpty).getOrElse(s"backoffice-$brand")
    yield Settings(brand, realm, client, address)

  private def required(env: Map[String, String], name: String): Either[String, String] =
    env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$name is not set: the service is deployed by the modules tool")

  private def uri(name: String, v: String): Either[String, URI] =
    Try(URI.create(v.stripSuffix("/"))).toOption.filter(u => u.getScheme == "https" || u.getScheme == "http")
      .toRight(s"$name: '$v' is not an http or https address")
