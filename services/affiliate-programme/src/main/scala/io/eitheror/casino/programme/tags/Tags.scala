package io.eitheror.casino.programme.tags

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore}
import io.eitheror.casino.programme.accounts.Secrets
import io.eitheror.casino.programme.links.SigningKeys

import java.time.Instant

/** Tags signed under the programme's newest live key, its private half read from the store (research R10). */
object Tags:
  def sign(client: ComponentClient, store: SecretStore, affiliate: String, link: String, campaign: Option[String],
      subIds: Vector[String], at: Instant): Either[String, String] =
    client.forKeyValueEntity(EntityId(SigningKeys.Id)).call(SigningKeys.get).invoke().signing match
      case None => Left("the programme has no signing key yet")
      case Some(key) =>
        Secrets(store).get(Secrets.signingKey(key.kid)) match
          case Left(e)              => Left(e.reason)
          case Right(None)          => Left(s"the signing key ${key.kid} is not in the store")
          case Right(Some(private8)) => TagSigner.sign(affiliate, link, campaign, subIds, at, key.kid, private8)
