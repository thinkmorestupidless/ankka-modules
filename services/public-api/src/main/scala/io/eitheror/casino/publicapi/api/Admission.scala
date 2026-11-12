package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.publicapi.contract.{Refusal, Refusals}
import io.eitheror.casino.publicapi.keys.{AccessKey, Admit, RawKeys}

import java.time.Instant

/** A request its key admitted: the key, the identifying fields it may read and its requests per minute. */
final case class Admitted(keyId: String, identifyingFields: Set[String], requestsPerMinute: Int)

/**
 * contracts/routes.md's pipeline steps 2 and 3: `Authorization: Bearer ak_<keyId>_<secret>`; absent is `key_missing`,
 * any other shape `key_invalid` without a lookup; then `AccessKey(keyId).admit(digest, now)`, read on every request so
 * a change applies from the next one (FR-019).
 */
object Admission:
  def presented(authorization: Option[String]): Either[Refusal, (String, String)] =
    authorization match
      case None => Left(Refusals.keyMissing)
      case Some(h) =>
        Option(h).filter(_.startsWith("Bearer ")).flatMap(v => RawKeys.parse(v.stripPrefix("Bearer ")))
          .map(p => p.keyId -> RawKeys.digest(p.whole)).toRight(Refusals.keyInvalid)

  def admit(components: ComponentClient, authorization: Option[String], now: Instant): Either[Refusal, Admitted] =
    presented(authorization).flatMap { (keyId, digest) =>
      val a = components.forEventSourcedEntity(EntityId(keyId)).call(AccessKey.admit).invoke(Admit(digest, now))
      a.refused match
        case Some("key_expired")  => Left(Refusals.keyExpired)
        case Some("key_disabled") => Left(Refusals.keyDisabled)
        case Some(_)              => Left(Refusals.keyInvalid)
        case None                 => Right(Admitted(keyId, a.identifyingFields, a.requestsPerMinute))
    }
