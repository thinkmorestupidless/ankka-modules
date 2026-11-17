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
final case class Settings(brand: String, realm: URI, client: String, address: URI, clientSecret: String = "", backChannel: Option[URI] = None):
  override def toString: String = s"Settings($brand, $realm, $client, $address, secret ${if clientSecret.isEmpty then "unset" else "set"})"

  /**
   * Where this service itself calls the realm: `KEYCLOAK_URL` with the issuer's realm path when the
   * installation names an in-cluster address (the issuer's host may be reachable only from outside, as on
   * kind), else the issuer. The browser is always sent to the issuer.
   */
  def realmCalls: URI = backChannel.fold(realm)(b => URI.create(s"${b.toString.stripSuffix("/")}${realm.getRawPath}"))

  /** The realm's token, revocation and end-session addresses, by Keycloak's layout. */
  def tokenUrl: URI      = URI.create(s"$realmCalls/protocol/openid-connect/token")
  def revokeUrl: URI     = URI.create(s"$realmCalls/protocol/openid-connect/revoke")
  def logoutUrl: URI     = URI.create(s"$realmCalls/protocol/openid-connect/logout")
  def authorizeUrl: URI  = URI.create(s"$realm/protocol/openid-connect/auth")
  def callback: URI      = URI.create(s"$address/auth/callback")

object Settings:

  /** The client's secret's configuration key (amendment D3; `<slug>-backoffice/CLIENT_SECRET` in the secrets file). */
  val ClientSecret = "backoffice-client-secret"

  /** The explicit local mode, where the brand's client may have no secret (a dev realm's). */
  val LocalMode = "BACKOFFICE_LOCAL"

  /**
   * The settings from the environment, refused naming the variable when one cannot be read. An empty
   * client secret is refused outside [[LocalMode]]: without it every sign-in fails as `realm_unavailable`.
   */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    for
      brand   <- required(env, "CASINO_BRAND")
      realm   <- required(env, "ANKKA_AUTH_STAFF_ISSUER").flatMap(uri("ANKKA_AUTH_STAFF_ISSUER", _))
      address <- required(env, "BACKOFFICE_HOST").flatMap(uri("BACKOFFICE_HOST", _))
      client   = env.get("BACKOFFICE_CLIENT").map(_.trim).filter(_.nonEmpty).getOrElse(s"backoffice-$brand")
      secret  <- env.get("BACKOFFICE_CLIENT_SECRET").map(_.trim).filter(_.nonEmpty) match
                   case Some(s)                                           => Right(s)
                   case None if env.get(LocalMode).map(_.trim).contains("true") => Right("")
                   case None =>
                     Left(s"BACKOFFICE_CLIENT_SECRET is not set: every sign-in at $client would fail. It is the brand's " +
                       s"`backoffice-client-secret`; set $LocalMode=true only to run against a realm whose client needs none")
      calls   <- env.get("KEYCLOAK_URL").map(_.trim).filter(_.nonEmpty) match
                   case Some(v) => uri("KEYCLOAK_URL", v).map(Some(_))
                   case None    => Right(None)
    yield Settings(brand, realm, client, address, secret, calls)

  private def required(env: Map[String, String], name: String): Either[String, String] =
    env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$name is not set: the service is deployed by the modules tool")

  private def uri(name: String, v: String): Either[String, URI] =
    Try(URI.create(v.stripSuffix("/"))).toOption.filter(u => u.getScheme == "https" || u.getScheme == "http")
      .toRight(s"$name: '$v' is not an http or https address")
