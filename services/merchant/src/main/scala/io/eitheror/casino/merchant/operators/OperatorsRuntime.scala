package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.sdk.{SecretStore, TimerScheduler}
import io.eitheror.casino.merchant.{Json, Settings}
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.security.Secrets

/**
 * How the operators' routes and the sync reach the realm and the invitation provider: over HTTP with
 * credentials from the secret store in a deployment, fakes in the suites. Set when the service is wired.
 */
object OperatorsRuntime:
  /**
   * The realm's admin credentials: the project secret `keycloak-operators-admin` the manifest renders as
   * env (`KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD`), else this service's own store entry
   * `keycloak/operators-admin` (`{username, password}`).
   */
  def adminCredentials(store: SecretStore, env: Map[String, String] = sys.env): Option[KeycloakAdmin.Credentials] =
    (env.get("KEYCLOAK_ADMIN_USERNAME"), env.get("KEYCLOAK_ADMIN_PASSWORD")) match
      case (Some(u), Some(p)) if u.nonEmpty && p.nonEmpty => Some(KeycloakAdmin.Credentials(u, p))
      case _ =>
        Secrets(store).get(Secrets.KeycloakAdmin).toOption.flatten.flatMap(Json.parseObject)
          .map(o => KeycloakAdmin.Credentials(o.textOr("username", ""), o.textOr("password", "")))

  /** The invitation provider's key and templates, when there are any: `CUSTOMERIO_*` as env, else the store's entry. */
  def customerIo(store: SecretStore, env: Map[String, String] = sys.env): Option[Invitations.CustomerIo] =
    (env.get("CUSTOMERIO_APP_API_KEY"), env.get("CUSTOMERIO_TRANSACTIONAL_ID")) match
      case (Some(k), Some(t)) if k.nonEmpty && t.nonEmpty =>
        Some(Invitations.CustomerIo(k, t, env.get("CUSTOMERIO_PASSWORD_RESET_TRANSACTIONAL_ID").filter(_.nonEmpty)))
      case _ =>
        Secrets(store).get(Secrets.Engagement).toOption.flatten.flatMap(Json.parseObject)
          .map(o => Invitations.CustomerIo(o.textOr("appApiKey", ""), o.textOr("transactionalId", ""), o.text("passwordResetTransactionalId")))

  @volatile var keycloak: (Settings, SecretStore) => KeycloakAdmin = (s, store) =>
    KeycloakAdmin.http(s.keycloakUrl, s.operatorsRealm, () => adminCredentials(store))

  @volatile var invitations: (Settings, SecretStore) => Invitations = (s, store) =>
    Invitations.customerIo(s.engagementRegion, () => customerIo(store))

  /** The timer runtime's scheduler, once the service has started. */
  @volatile var scheduler: Option[() => TimerScheduler] = None
