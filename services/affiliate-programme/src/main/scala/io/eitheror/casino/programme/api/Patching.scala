package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Bodies.AccountPatch
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.postbacks.Addresses
import io.eitheror.casino.programme.sealing.Sealing

/** An account's fields changed from a patch: the identity re-sealed under its key, the rest as named. */
final class Patching(api: Api):
  /** None when done; a refusal otherwise. A manager may change the fields the affiliate cannot. */
  def apply(affiliate: String, p: AccountPatch, by: String, manager: Boolean): Option[Respond[Bytes]] =
    p.postbackUrl.filter(_.trim.nonEmpty).flatMap(Addresses.refusal(_, api.settings.plainHttp))
      .map(reason => refuse(400, Codes.PostbackUrlRefused, reason))
      .orElse(patched(affiliate, p, by, manager))

  private def patched(affiliate: String, p: AccountPatch, by: String, manager: Boolean): Option[Respond[Bytes]] =
    val entity = api.components.forEventSourcedEntity(EntityId(affiliate))
    val a      = api.account(affiliate)
    val identityChanged = Seq(p.name, p.displayName, p.companyName, p.website, p.trafficSource, p.paymentMethod).exists(_.isDefined)
    val sealedOk: Either[String, Unit] =
      if !identityChanged then Right(())
      else
        Secrets(api.secrets).existingDataKey(affiliate).left.map(_.reason).flatMap {
          case None => Left(Codes.Erased)
          case Some(key) =>
            Sealing.open(a.identity, key, affiliate).map { id =>
              val next = id.copy(
                name = p.name.getOrElse(id.name),
                displayName = p.displayName.orElse(id.displayName),
                companyName = p.companyName.orElse(id.companyName),
                website = p.website.orElse(id.website),
                trafficSource = p.trafficSource.orElse(id.trafficSource),
                paymentMethod = p.paymentMethod.orElse(id.paymentMethod)
              )
              entity.call(Affiliate.updateIdentity).invoke(UpdateIdentity(Sealing.seal(next, key, affiliate), a.emailDigest, by, api.now())): Unit
            }
        }
    sealedOk match
      case Left(Codes.Erased) => Some(refuse(409, Codes.Erased))
      case Left(e)            => Some(refuse(503, "store_unavailable", e))
      case Right(_) =>
        val fields =
          if manager then AccountFields(minimumPayout = p.minimumPayout, postbackUrl = p.postbackUrl, managerId = p.managerId, group = p.group, tags = p.tags)
          else AccountFields(postbackUrl = p.postbackUrl)
        if fields != AccountFields() then entity.call(Affiliate.update).invoke(UpdateAccount(fields, by, api.now())): Unit
        None
