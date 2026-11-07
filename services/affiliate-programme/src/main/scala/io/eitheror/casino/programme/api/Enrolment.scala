package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.sealing.{Digest, Identity, Sealing}

/**
 * How an account comes to be, the same for an application (P7), an email invitation (M20) and an
 * invite link (P4): its email indexed by digest (FR-003), its data key made, its identity sealed (R9).
 */
final class Enrolment(api: Api):
  /** An address the programme takes: trimmed, with an `@`, at most 254 characters. */
  def address(email: Option[String]): Option[String] = email.map(_.trim).filter(e => e.contains('@') && e.length <= 254)

  /** The email's digest and the live account it already names, if any; or why the store could not say. */
  def lookup(email: String): Either[String, (String, Option[String])] =
    Secrets(api.secrets).emailIndexKey.left.map(_.reason).map { key =>
      val digest = Digest.of(email, key)
      (digest, api.components.forKeyValueEntity(EntityId(digest)).call(EmailIndex.get).invoke().affiliate.filter(a => api.account(a).exists))
    }

  /**
   * The email's index entry for `affiliate`. One whose account was never made (an enrolment that failed
   * after its claim) is let go first, or the address could never register again; a live holder refuses.
   */
  def claim(digest: String, affiliate: String): Unit =
    val index = api.components.forKeyValueEntity(EntityId(digest))
    index.call(EmailIndex.get).invoke().affiliate.filter(h => h != affiliate && !api.account(h).exists)
      .foreach(orphan => index.call(EmailIndex.release).invoke(orphan): Unit)
    index.call(EmailIndex.claim).invoke(affiliate): Unit

  /** A new account under `affiliate`, its email claimed in the index. */
  def open(affiliate: String, digest: String, identity: Identity, parent: Option[String], overrideBp: Int, fields: AccountFields,
      by: String): Either[String, Unit] =
    claim(digest, affiliate)
    Secrets(api.secrets).dataKey(affiliate).left.map(_.reason).map { key =>
      val sealedIdentity = Sealing.seal(identity.copy(email = Digest.normalised(identity.email)), key, affiliate)
      api.components.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.createAccount)
        .invoke(CreateAffiliate(affiliate, sealedIdentity, digest, parent, overrideBp, fields, by, api.now())): Unit
    }
