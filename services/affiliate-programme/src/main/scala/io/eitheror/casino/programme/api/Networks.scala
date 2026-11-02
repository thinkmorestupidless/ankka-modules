package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.{Audit, AuditEntry}
import io.eitheror.casino.programme.api.Answers.given
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.comms.{EmailSend, SendEmail}
import io.eitheror.casino.programme.ledger.{Figure, Revenue}
import io.eitheror.casino.programme.network.Network
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.sealing.Identity

import java.time.Instant

/** The network's answers, the original's shapes (`S/NetworkTree.scala`), with figures per brand and currency. */
object NetworkAnswers:
  final case class Held(brandId: String, status: String, planId: Option[String])
  /** A node of the tree; `status` and `planId` are its first membership's among the brands read. */
  final case class Node(affiliateId: String, displayName: String, status: String, planId: String, overridePercent: BigDecimal,
      memberships: Vector[Held], children: Vector[Node], revenueForPeriod: Option[Vector[Figure]])
  final case class Health(totalSubs: Int, activeSubs: Int, pendingApprovals: Int, suspendedSubs: Int)
  final case class Sub(affiliateId: String, displayName: String, status: String, overridePercent: BigDecimal, planId: String,
      createdAt: Option[Instant], memberships: Vector[Held], revenueForPeriod: Option[Vector[Figure]] = None)
  final case class Invited(affiliateId: String, email: String)
  final case class InviteBody(email: Option[String] = None, name: Option[String] = None, overridePercent: Option[Percent] = None,
      planId: Option[String] = None, brands: Option[Vector[String]] = None)
  final case class SubPatch(planId: Option[String] = None, overridePercent: Option[Percent] = None)

  private val heldCodec: JsonValueCodec[Vector[Held]] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))
  private val figuresCodec: JsonValueCodec[Vector[Figure]] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true).withTransientDefault(false).withTransientEmpty(false))

  /** Written by hand: the macro does not derive this recursive type. A tree is only ever answered, never read. */
  given JsonValueCodec[Node] = new JsonValueCodec[Node]:
    def nullValue: Node = null
    def decodeValue(in: JsonReader, default: Node): Node = in.decodeError("a network tree is not read")
    def encodeValue(n: Node, out: JsonWriter): Unit =
      out.writeObjectStart()
      out.writeKey("affiliateId"); out.writeVal(n.affiliateId)
      out.writeKey("displayName"); out.writeVal(n.displayName)
      out.writeKey("status"); out.writeVal(n.status)
      out.writeKey("planId"); out.writeVal(n.planId)
      out.writeKey("overridePercent"); out.writeVal(n.overridePercent)
      out.writeKey("memberships"); heldCodec.encodeValue(n.memberships, out)
      out.writeKey("children")
      out.writeArrayStart()
      n.children.foreach(encodeValue(_, out))
      out.writeArrayEnd()
      n.revenueForPeriod.foreach { f => out.writeKey("revenueForPeriod"); figuresCodec.encodeValue(f, out) }
      out.writeObjectEnd()
  given JsonValueCodec[Health]      = ProgrammeCodecs.wire[Health]
  given subsCodec: JsonValueCodec[Vector[Sub]] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true).withTransientDefault(false).withTransientEmpty(false))
  given JsonValueCodec[Invited]     = ProgrammeCodecs.wire[Invited]
  given JsonValueCodec[InviteBody]  = ProgrammeCodecs.wire[InviteBody]
  given JsonValueCodec[SubPatch]    = ProgrammeCodecs.wire[SubPatch]

import NetworkAnswers.{*, given}

/**
 * The network (FR-027, FR-028) as both sides read and act on it: an affiliate within their own subtree,
 * acting as an ancestor; a manager at their brand, acting as the operator. Every action on a sub is
 * audited as its actor's.
 */
final class Networks(api: Api):
  /** The children of an account, from the view, each once. */
  def childrenOf(affiliate: String): Vector[AffiliateState] =
    api.views.forView(Accounts).ask(Accounts.childrenOf, "parent" -> affiliate).filter(_.exists)

  /** Every descendant of `root` to `maxDepth` levels, each once. */
  def descendants(root: String, maxDepth: Int = Access.MaxDepth): Vector[String] =
    Network.descendants(root, a => childrenOf(a).map(_.id), maxDepth).map(_._1)

  /** Whether `sub` is strictly below `ancestor`. */
  def below(ancestor: String, sub: String): Boolean = sub != ancestor && Access.reaches(ancestor, sub, api.parentOf)

  private def held(affiliate: String, brands: Vector[String]): Vector[Held] =
    brands.map(b => api.membership(affiliate, b)).filter(_.exists).map(m => Held(m.brand, m.status.getOrElse(""), m.plan))

  private def name(a: AffiliateState): String =
    api.identity(a.id, a).toOption.flatten.map(id => id.displayName.getOrElse(id.name)).getOrElse("")

  /** A network read's status: active if any membership is, else pending, else suspended, else the first's. */
  private def statusOf(h: Vector[Held]): String =
    Seq(Membership.Active, Membership.Pending, Membership.Suspended).find(s => h.exists(_.status == s))
      .orElse(h.headOption.map(_.status)).getOrElse("")

  private def figures(affiliate: String, brands: Vector[String], range: Option[Periods.Range]): Option[Vector[Figure]] =
    range.map(r => Revenue.read(api.reads, Some(affiliate), brands.map(b => b -> api.brand(b).zone), r))

  /** M14, O10: the subtree to `maxDepth` (3 by default, 6 at most, as the original), revenue for `period` when named. */
  def tree(root: String, brands: Vector[String], maxDepth: Option[String], period: Option[String]): Respond[Bytes] =
    val depth = maxDepth.flatMap(_.toIntOption).map(d => math.max(0, math.min(d, 6))).getOrElse(3)
    period.map(p => Periods.Range.parse(p).toRight(p)) match
      case Some(Left(_)) => refuse(400, Codes.InvalidPeriod, "period is yyyy-MM, yyyy-MM..yyyy-MM, yyyy-MM-dd..yyyy-MM-dd or all")
      case other =>
        val range = other.flatMap(_.toOption)
        def node(a: AffiliateState, d: Int, seen: Set[String]): Node =
          val h        = held(a.id, brands)
          val children = if d >= depth || seen(a.id) then Vector.empty else childrenOf(a.id).map(node(_, d + 1, seen + a.id))
          Node(a.id, name(a), statusOf(h), h.headOption.flatMap(_.planId).getOrElse(""), Percent.show(a.overrideBp), h, children,
            figures(a.id, brands, range))
        val a = api.account(root)
        if !a.exists then refuse(404, Codes.AccountNotFound) else ok(node(a, 0, Set.empty))

  /** M13, O9: counts over the whole subtree. */
  def health(root: String, brands: Vector[String]): Respond[Bytes] =
    val statuses = descendants(root).map(d => statusOf(held(d, brands)))
    ok(Health(statuses.size, statuses.count(_ == Membership.Active), statuses.count(_ == Membership.Pending),
      statuses.count(_ == Membership.Suspended)))

  /** M21, O27: the direct subs, revenue for `period` when named. */
  def subs(parent: String, brands: Vector[String], period: Option[String]): Respond[Bytes] =
    period.map(p => Periods.Range.parse(p).toRight(p)) match
      case Some(Left(_)) => refuse(400, Codes.InvalidPeriod, "period is yyyy-MM, yyyy-MM..yyyy-MM, yyyy-MM-dd..yyyy-MM-dd or all")
      case other =>
        val range = other.flatMap(_.toOption)
        ok(childrenOf(parent).map { a =>
          val h = held(a.id, brands)
          Sub(a.id, name(a), statusOf(h), Percent.show(a.overrideBp), h.headOption.flatMap(_.planId).getOrElse(""), a.createdAt, h,
            figures(a.id, brands, range))
        })

  def audit(affiliate: String, brand: Option[String], action: String, actor: String, details: Map[String, String] = Map.empty): Unit =
    api.components.forKeyValueEntity(EntityId(AuditEntry.newId())).call(AuditEntry.write)
      .invoke(Audit(affiliate, brand, action, actor, api.now(), details = details)): Unit

  /**
   * M17 to M19 and O11 to O13: a transition of the sub's membership at `brand`, `kind` saying who acts.
   * An approval puts the sub on the plan its parent holds at the brand, or the brand's default.
   */
  def transition(sub: String, brand: String, action: String, actor: String, kind: String): Respond[Bytes] =
    if api.brand(brand).retired.isDefined then refuse(409, OperatorEndpoint.BrandRetired, brand)
    else transitionAt(sub, brand, action, actor, kind)

  private def transitionAt(sub: String, brand: String, action: String, actor: String, kind: String): Respond[Bytes] =
    val m = api.membership(sub, brand)
    if !m.exists then refuse(409, Codes.NotAMember, brand)
    else
      val entity = api.components.forEventSourcedEntity(Membership.entityId(sub, brand))
      val done: Either[Respond[Bytes], Unit] = action match
        case "approve" =>
          api.parentOf(sub).flatMap(p => api.membership(p, brand).plan).orElse(api.brand(brand).defaultPlan) match
            case None => Left(refuse(409, Codes.NoPlanAssigned, s"neither the parent nor $brand has a plan to approve on"))
            case Some(plan) => Right(entity.call(Membership.approve).invoke(Approve(plan, actor, api.now())): Unit)
        case "suspend"    => Right(entity.call(Membership.suspend).invoke(Suspend(actor, kind, api.now())): Unit)
        case "reactivate" => Right(entity.call(Membership.reactivate).invoke(Reactivate(actor, kind, api.now())): Unit)
        case other        => Left(refuse(404, "not_found", other))
      done.fold(identity, { _ =>
        audit(sub, Some(brand), s"SUB_AFFILIATE_${action.toUpperCase}", actor, Map("as" -> kind))
        ok(Answers.Ok(true))
      })

  /** M16: a parent changes a sub's plan at `brand` and its override percentage. */
  def patch(sub: String, brand: Option[String], p: SubPatch, actor: String): Respond[Bytes] =
    if p.planId.isEmpty && p.overridePercent.isEmpty then invalid("no updatable fields provided")
    else
      p.planId match
        case Some(_) if brand.isEmpty => invalid("brand is required to change a plan")
        case Some(plan) if !api.plan(brand.get, plan).exists => refuse(404, Codes.PlanNotFound, plan)
        case Some(_) if !api.membership(sub, brand.get).exists => refuse(409, Codes.NotAMember, brand.get)
        case _ =>
          p.planId.foreach { plan =>
            api.components.forEventSourcedEntity(Membership.entityId(sub, brand.get)).call(Membership.setPlan)
              .invoke(SetPlan(plan, actor, api.now())): Unit
            audit(sub, brand, "PLAN_CHANGED", actor, Map("planId" -> plan))
          }
          p.overridePercent.foreach { o =>
            api.components.forEventSourcedEntity(EntityId(sub)).call(Affiliate.setOverride).invoke(SetOverride(o.bp, actor, api.now())): Unit
            audit(sub, brand, "OVERRIDE_CHANGED", actor, Map("overridePercent" -> Percent.show(o.bp).toString))
          }
          ok(Answers.Ok(true))

  /**
   * M20: an email invitation (FR-027): a new account under the parent, active at once at each brand named
   * (every brand the parent is active at when none is), on the plan named, or the parent's, or the
   * brand's default; then the sub is sent a link to set a password.
   */
  def invite(parent: String, b: InviteBody, brandParam: Option[String]): Respond[Bytes] =
    val enrolment = Enrolment(api)
    val email     = enrolment.address(b.email)
    val brands    = b.brands.filter(_.nonEmpty).orElse(brandParam.map(Vector(_)))
      .getOrElse(api.membershipsOf(parent).filter(_.active).map(_.brand).distinct.sorted)
    lazy val plans: Either[Respond[Bytes], Vector[(String, String)]] =
      brands.foldLeft[Either[Respond[Bytes], Vector[(String, String)]]](Right(Vector.empty)) { (acc, brand) =>
        acc.flatMap { done =>
          val parentAt = api.membership(parent, brand)
          if !parentAt.active then Left(refuse(409, Codes.NotAMember, brand))
          else
            b.planId match
              case Some(p) =>
                val plan = api.plan(brand, p)
                if !plan.exists then Left(refuse(400, Codes.PlanNotFound, p))
                else if plan.status != Plan.Active then Left(refuse(400, "plan_not_active", p))
                else Right(done :+ (brand -> p))
              case None =>
                parentAt.plan.orElse(api.brand(brand).defaultPlan)
                  .toRight(refuse(409, Codes.NoPlanAssigned, brand)).map(p => done :+ (brand -> p))
        }
      }
    if email.isEmpty then invalid("email is required")
    else if brands.isEmpty then refuse(409, Codes.NotAMember, "the inviter is active at no brand")
    else
      plans.fold(identity, { onPlans =>
        enrolment.lookup(email.get).fold(e => refuse(503, "store_unavailable", e), {
          case (_, Some(_)) => refuse(409, Codes.EmailRegistered)
          case (digest, None) =>
            val sub = Affiliate.newId()
            val identity = Identity(b.name.getOrElse(email.get), b.name, email.get)
            enrolment.open(sub, digest, identity, Some(parent), b.overridePercent.fold(0)(_.bp), AccountFields(), parent)
              .fold(e => refuse(503, "store_unavailable", e), { _ =>
                onPlans.foreach { (brand, plan) =>
                  api.components.forEventSourcedEntity(Membership.entityId(sub, brand)).call(Membership.activateInvited)
                    .invoke(ActivateInvited(sub, brand, plan, Application(api.now(), None, "invitation", Some(parent)), parent)): Unit
                }
                signIn(sub, onPlans.head._1)
                audit(sub, Some(onPlans.head._1), "SUB_AFFILIATE_INVITED", parent, Map("brands" -> onPlans.map(_._1).mkString(",")))
                answer(201, Invited(sub, email.get))
              })
        })
      })

  /** The invitation to set a password: a token (72 hours) and its email (`AffiliateInvite`, as the original). */
  def signIn(affiliate: String, brand: String): Unit =
    val token = Token.mint()
    api.components.forKeyValueEntity(Token.idOf(token)).call(Token.issue)
      .invoke(IssueToken(affiliate, Token.Invite, Some(brand), api.now().plus(Token.life(Token.Invite)))): Unit
    val link = s"${api.settings.portalBaseUrl}/accept-invitation?token=$token"
    api.components.forWorkflow(EntityId(s"invite-${Token.idOf(token)}")).call(EmailSend.start)
      .invoke(SendEmail(affiliate, brand, "AffiliateInvite", Map("set_password_url" -> link, "link" -> link))): Unit
