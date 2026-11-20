package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.access.BrandRoles
import io.eitheror.casino.merchant.deposits.DepositRuntime

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

/**
 * Brings the realm to a brand's giving (spec 032 H6a, research R4): a consumer of the operator entity,
 * so it sees one operator's givings in order, at least once, and a change it fails is delivered again.
 *
 *  1. When a later giving is recorded for the brand, or this one is no longer pending, it does nothing.
 *  2. It creates each realm role the giving names, assigns what is asked and not held, and removes every
 *     role named for the brand that is held and not asked, whoever gave it (`brand_<slug>` stays), except
 *     the programme's (`BrandRoles.ProgrammeGiven`).
 *  3. In force: `BrandRolesApplied`. Refused by the realm (or the operator is not in it): `BrandRolesFailed`,
 *     not tried again. Unreachable: it waits, then fails the change, so it is delivered again. Erring (500):
 *     the same, until [[MaxErring]] in a row, when the giving fails, so one bad request does not hold back
 *     its slice for ever.
 *
 * Every realm write sets the brand's roles to the asked set, so applying a giving twice is safe.
 */
final class BrandRolesApplier(context: ConsumerContext) extends Consumer[OperatorEvent, Unit]:
  def onMessage(event: OperatorEvent): Effect = event match
    case OperatorEvent.BrandRolesAsked(brand, _, version, _) =>
      val id     = messageContext.subject
      val entity = context.componentClient.forEventSourcedEntity(EntityId(id))
      entity.call(Operator.get).invoke().givings.get(brand).filter(g => g.version == version && g.state == GivingState.Pending) match
        case None => effects.ignore()
        case Some(giving) =>
          val key = s"$id/$brand"
          BrandRolesApplier.apply(OperatorsRuntime.keycloak(DepositRuntime.settings, context.secrets), id, brand, giving.roles) match
            case Right(()) =>
              BrandRolesApplier.failures.remove(key)
              entity.call(Operator.brandRolesApplied).invoke(BrandRolesDone(brand, version, DepositRuntime.now())): Unit
              effects.done()
            case Left(e) if RealmError.retryable(e) && !BrandRolesApplier.givesUp(e, BrandRolesApplier.failures.getOrDefault(key, 0)) =>
              val wait = BrandRolesApplier.backoff(BrandRolesApplier.failures.merge(key, 1, _ + _))
              Thread.sleep(wait.toMillis)
              throw IllegalStateException(s"the realm could not apply version $version of $brand's giving to $id: $e")
            case Left(e) =>
              BrandRolesApplier.failures.remove(key)
              entity.call(Operator.brandRolesFailed).invoke(BrandRolesRefused(brand, version, BrandRolesApplier.reason(e), DepositRuntime.now())): Unit
              effects.done()
    case _ => effects.ignore()

object BrandRolesApplier extends Consumer.Companion[BrandRolesApplier, OperatorEvent, Unit](
      componentId = ComponentId("brand-roles-applier"),
      source = ChangeSource.eventsOf(Operator)
    ):
  def create(ctx: ConsumerContext) = new BrandRolesApplier(ctx)

  /** 500s in a row for one operator and brand before the giving fails: about 5 minutes at the ceiling. */
  val MaxErring = 30

  /** Whether the applier stops trying `e` after `attempts` failed tries: only a realm erring, at [[MaxErring]]. */
  def givesUp(e: RealmError, attempts: Int): Boolean = e match
    case _: RealmError.Erring => attempts >= MaxErring
    case _                    => false

  /** The first wait and the ceiling (research R4): a giving is in force within 30 s of the realm answering again (SC-002). */
  val MinBackoff: FiniteDuration = 1.second
  val MaxBackoff: FiniteDuration = 10.seconds

  /**
   * How long to wait before failing the change after `attempt` unreachable answers in a row: doubling from
   * 1 s to 10 s. ankka 0.13 delivers a failed change again at once, so the wait is the applier's own; it
   * holds back that slice's later changes, which is right while nothing can be applied.
   */
  def backoff(attempt: Int): FiniteDuration =
    val doubled = MinBackoff * math.pow(2, (attempt - 1).max(0).min(10)).toLong
    if doubled > MaxBackoff then MaxBackoff else doubled

  /** Unreachable answers in a row, by operator and brand; reset when the giving is in force or refused. */
  private[operators] val failures = ConcurrentHashMap[String, Integer]()

  /**
   * The realm brought to `roles` for `brand`: roles named for it created and assigned, others named for it
   * removed, except the programme's ([[BrandRoles.ProgrammeGiven]]), which no giving gives or takes away.
   */
  def apply(realm: KeycloakAdmin, id: String, brand: String, roles: Set[String]): Either[RealmError, Unit] =
    val target = roles.map(BrandRoles.nameIn(brand, _)) + s"brand_$brand"
    def each(rs: Set[String])(f: String => Either[RealmError, Unit]) =
      rs.toVector.sorted.foldLeft[Either[RealmError, Unit]](Right(()))((acc, r) => acc.flatMap(_ => f(r)))
    for
      held <- realm.realmRoles(id)
      _    <- each(target -- held)(realm.ensureRole)
      _    <- each(target -- held)(realm.assignRole(id, _))
      _    <- each(held.filter(BrandRoles.givenBy(brand, _)) -- target)(realm.removeRole(id, _))
    yield ()

  /** The realm's refusal as the brand reads it. */
  def reason(e: RealmError): String = e match
    case RealmError.NotFound(_)        => "the operator is not in the realm"
    case RealmError.Refused(_, reason) => reason
    case RealmError.Erring(why)        => s"the realm kept failing: $why"
    case RealmError.AlreadyExists(e)   => s"$e exists"
    case other                         => other.toString
