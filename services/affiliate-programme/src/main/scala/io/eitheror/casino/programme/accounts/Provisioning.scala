package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.Wiring
import io.eitheror.casino.programme.links.{CreateLink, Link}
import io.eitheror.casino.programme.sealing.{Identity, Sealing}
import org.slf4j.LoggerFactory

import java.time.Instant

/** The calls `Provisioner` makes, so its suite can answer them without a service. */
trait ProvisioningCalls:
  def account(affiliate: String): AffiliateState
  def membership(affiliate: String, brand: String): MembershipState
  def identity(affiliate: String, account: AffiliateState): Option[Identity]
  def setKeycloakUser(affiliate: String, user: String): Unit
  def createLink(c: CreateLink): Unit

object ProvisioningCalls:
  def of(client: ComponentClient, secrets: SecretStore, now: () => Instant): ProvisioningCalls = new ProvisioningCalls:
    def account(affiliate: String): AffiliateState = client.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.get).invoke()
    def membership(affiliate: String, brand: String): MembershipState =
      client.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.get).invoke()
    def identity(affiliate: String, account: AffiliateState): Option[Identity] =
      Secrets(secrets).existingDataKey(affiliate) match
        case Left(e)          => throw IllegalStateException(s"the secret store: ${e.reason}")
        case Right(None)      => None
        case Right(Some(key)) => Sealing.open(account.identity, key, affiliate).toOption
    def setKeycloakUser(affiliate: String, user: String): Unit =
      client.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.update)
        .invoke(UpdateAccount(AccountFields(keycloakUser = Some(user)), "provisioning", now())): Unit
    def createLink(c: CreateLink): Unit = client.forKeyValueEntity(EntityId(c.id)).call(Link.createLink).invoke(c): Unit

/**
 * What a membership's change means in the realm (research R11): on activation the account's realm user,
 * made once and found by email after, holds `affiliate` and `brand_<slug>`, and the affiliate is given
 * a default link at the brand; when the membership is neither active nor suspended the brand's role is
 * removed. A realm that is not configured (a local run) is skipped; a realm that fails is retried.
 */
final class Provisioner(calls: ProvisioningCalls, realm: KeycloakAdmin, now: () => Instant):
  private val log = LoggerFactory.getLogger(classOf[Provisioner])

  def provision(affiliate: String, brand: String, event: Membership.Event): Unit =
    event match
      case _: Membership.Approved | _: Membership.ActivatedInvited =>
        grant(affiliate, brand)
        calls.createLink(CreateLink(Link.defaultIdOf(affiliate, brand), affiliate, brand, Some("default"), None, Map.empty,
          Vector.empty, None, None, "provisioning", now()))
      case _: Membership.MembershipReactivated => grant(affiliate, brand)
      case _ =>
        val m = calls.membership(affiliate, brand)
        if !m.status.exists(s => s == Membership.Active || s == Membership.Suspended) then
          calls.account(affiliate).keycloakUser.foreach(user => done(realm.removeRole(user, s"brand_$brand")))

  private def grant(affiliate: String, brand: String): Unit =
    val account = calls.account(affiliate)
    if !account.exists || account.closed.isDefined || account.erased.isDefined then ()
    else
      userOf(affiliate, account).foreach { user =>
        done(realm.assignRole(user, "affiliate"))
        done(realm.assignRole(user, s"brand_$brand"))
      }

  /** The account's realm user: the one it holds, the one its email already names, or a new one. */
  private def userOf(affiliate: String, account: AffiliateState): Option[String] =
    account.keycloakUser.orElse {
      calls.identity(affiliate, account).flatMap { id =>
        val found = done(realm.findByEmail(id.email)).flatten
        val user  = found.orElse {
          val (first, last) = KeycloakAdmin.names(id.displayName.orElse(Some(id.name)))
          done(realm.createUser(id.email, first, last, KeycloakAdmin.affiliateAttributes(affiliate)))
        }
        user.foreach(calls.setKeycloakUser(affiliate, _))
        user
      }
    }

  private def done[A](r: Either[RealmError, A]): Option[A] = r match
    case Right(a)                     => Some(a)
    case Left(RealmError.NotConfigured(_)) =>
      log.debug("no realm configured: provisioning skipped")
      None
    case Left(RealmError.AlreadyExists(_)) => None
    case Left(other)                  => throw IllegalStateException(s"the realm: $other")

final class Provisioning(context: ConsumerContext) extends Consumer[Membership.Event, Unit]:
  def onMessage(event: Membership.Event): Effect =
    messageContext.subject.split('/') match
      case Array(affiliate, brand) =>
        Provisioner(ProvisioningCalls.of(context.componentClient, context.secrets, () => Wiring.clock.get().instant()), Wiring.realm.get(),
          () => Wiring.clock.get().instant()).provision(affiliate, brand, event)
      case _ => ()
    effects.done()

object Provisioning extends Consumer.Companion[Provisioning, Membership.Event, Unit](
      componentId = ComponentId("provisioning"),
      source = ChangeSource.eventsOf(Membership)
    ):
  def create(ctx: ConsumerContext) = new Provisioning(ctx)
