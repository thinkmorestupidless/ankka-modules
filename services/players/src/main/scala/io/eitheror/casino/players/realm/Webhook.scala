package io.eitheror.casino.players.realm

import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant

/**
 * What the realm's audit listener posts (contracts/realm.md; the original's `/internal/keycloak-webhook`):
 * `LOGIN`, `LOGOUT` (also synthesised from an admin `USER_SESSION DELETE`, with `userId` `-`) and
 * `SESSION_EXPIRED`, under `X-Webhook-Secret`.
 */
final case class WebhookEvent(kind: String, realmId: String, sessionId: String, userId: String, at: Instant)

object Webhook:
  val Kinds: Set[String] = Set("LOGIN", "LOGOUT", "SESSION_EXPIRED")

  /** The body read, or none when it is not one the listener sends. */
  def parse(body: String): Option[WebhookEvent] =
    Json.parseObject(body).flatMap { o =>
      for
        kind    <- o.text("type").map(_.toUpperCase).filter(Kinds)
        session <- o.text("sessionId")
      yield WebhookEvent(kind, o.textOr("realmId", ""), session, o.textOr("userId", "-"),
        o.long("timestamp").map(Instant.ofEpochMilli).getOrElse(Instant.EPOCH))
    }

  /** The header against the secret in constant time; an absent header or secret is refused. */
  def verify(header: Option[String], secret: Option[String]): Boolean =
    (header.map(_.trim).filter(_.nonEmpty), secret.filter(_.nonEmpty)) match
      case (Some(h), Some(s)) => MessageDigest.isEqual(h.getBytes(UTF_8), s.getBytes(UTF_8))
      case _                  => false
