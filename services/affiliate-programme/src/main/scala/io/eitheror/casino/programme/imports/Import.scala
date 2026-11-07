package io.eitheror.casino.programme.imports

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.*
import io.eitheror.casino.programme.brands.{BrandProgramme, CreatePlan, Fees, Plan, Thresholds, UpdateBrand, UpdatePlan}
import io.eitheror.casino.programme.commission.{Claim, FactType, LedgerFact}
import io.eitheror.casino.programme.ledger.{CommissionAccount, ImportFacts}
import io.eitheror.casino.programme.links.*
import io.eitheror.casino.programme.players.{ApplyFact, PlayerAttribution, PlayerFact}
import io.eitheror.casino.programme.sealing.Sealed
import io.eitheror.casino.programme.settlement.{ImportPayout, Payout, PayoutState}
import io.eitheror.casino.programme.tags.Tags

import java.time.Instant
import scala.concurrent.duration.*

/** An account as the import keeps it: its identity already sealed and its email digested by the route. */
final case class SealedAccount(account: BundleAccount, identity: Sealed, emailDigest: String)

/**
 * One import of a brand's bundle (FR-039, research R19), id `<brand>/<digest of the bundle>`: the same
 * bundle posted again is the same import. Each section is a step; a step does at most `Import.Batch`
 * items and comes back to itself, so a long section neither holds one step past its timeout nor
 * starts over when retried. Every write is one the target ignores the second time, so a later bundle
 * of the same data writes nothing either.
 */
final case class ImportState(
    brand: String = "",
    bundle: Option[Bundle] = None,
    accounts: Vector[SealedAccount] = Vector.empty,
    by: String = "",
    startedAt: Option[Instant] = None,
    section: Int = 0,
    cursor: Int = 0,
    counts: Map[String, Int] = Map.empty,
    finishedAt: Option[Instant] = None
):
  def started: Boolean  = startedAt.isDefined
  def finished: Boolean = finishedAt.isDefined

final case class StartImport(brand: String, bundle: Bundle, accounts: Vector[SealedAccount], by: String, at: Instant)

/** The writes each section makes, in the order the sections run: what a later section names, an earlier one made. */
final class Sections(client: ComponentClient, secrets: SecretStore):
  import Import.Section

  private def account(id: String)  = client.forEventSourcedEntity(EntityId(id))
  private def membership(a: String, b: String) = client.forEventSourcedEntity(Membership.entityId(a, b))

  private def tagOf(affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], at: Instant): String =
    Tags.sign(client, secrets, affiliate, link, campaign, subIds, at).fold(p => throw IllegalStateException(s"a tag for $link: $p"), identity)

  /** The sections of a state, each its item count and how to write one item; plans before the settings that name the default. */
  def of(s: ImportState): Vector[Section] =
    val b     = s.bundle.get
    val brand = s.brand
    Vector(
      Section("plans", b.plans.size, i => plan(brand, b.plans(i))),
      Section("settings", 1, _ => settings(brand, b)),
      Section("accounts", s.accounts.size, i => enrol(brand, s.accounts(i))),
      Section("links", b.links.size, i => link(brand, b.links(i))),
      Section("bonus-codes", b.bonusCodes.size, i => code(brand, b, b.bonusCodes(i))),
      Section("invite-links", b.inviteLinks.size, i => invite(brand, b.inviteLinks(i))),
      Section("creatives", b.creatives.size, i => creative(brand, b.creatives(i))),
      Section("notes", b.notes.size, i => note(brand, b.notes(i))),
      Section("audit", b.audit.size, i => audit(brand, b.audit(i))),
      Section("fraud-flags", b.fraudFlags.size, i => flag(brand, b.fraudFlags(i))),
      Section("players", b.players.size, i => player(brand, b.players(i))),
      Section("ledger", Import.ledgers(b).size, i => ledger(brand, b, Import.ledgers(b)(i))),
      Section("payouts", b.payouts.size, i => payout(brand, b.payouts(i)))
    )

  private def settings(brand: String, b: Bundle): Unit =
    val st = b.settings
    val update = UpdateBrand(
      fees = st.map(x => Fees(x.adminFeeBp, x.paymentFeeBp, x.providerFeeBp)),
      thresholds = st.map(x => Thresholds(x.bonusCostThreshold.getOrElse(3), x.chargebackThreshold.getOrElse(2))),
      payoutCycleDay = st.flatMap(_.payoutCycleDay),
      reportingZone = st.flatMap(_.reportingZone),
      defaultPlan = st.flatMap(_.defaultPlan).map(Plan.idOf(brand, _)),
      page = b.programme
    )
    client.forKeyValueEntity(EntityId(brand)).call(BrandProgramme.update).invoke(update): Unit

  private def plan(brand: String, p: BundlePlan): Unit =
    val id    = Plan.idOf(brand, p.name)
    val ref   = client.forKeyValueEntity(Plan.entityId(brand, id))
    if !ref.call(Plan.get).invoke().exists then
      ref.call(Plan.createPlan).invoke(CreatePlan(brand, p.name, p.planType, p.cpaAmount, p.cpaCurrency, p.revShareBp, p.minimumDeposit,
        p.negativeCarryover, "import", p.createdAt)): Unit
      if p.status != Plan.Active then ref.call(Plan.update).invoke(UpdatePlan(status = Some(p.status))): Unit

  private def enrol(brand: String, s: SealedAccount): Unit =
    val a = s.account
    client.forKeyValueEntity(EntityId(s.emailDigest)).call(EmailIndex.claim).invoke(a.id): Unit
    val fields = AccountFields(a.minimumPayout, a.postbackUrl, a.keycloakUser, a.termsVersion, a.termsAcceptedAt, a.managerId, a.group, a.tags)
    account(a.id).call(Affiliate.createAccount)
      .invoke(CreateAffiliate(a.id, s.identity, s.emailDigest, a.parent, a.overrideBp, fields, "import", a.createdAt)): Unit
    val m = a.membership
    val state = MembershipState(a.id, brand, Some(m.status), m.plan.map(Plan.idOf(brand, _)), m.revShareOverrideBp,
      Some(Application(m.appliedAt, a.termsVersion, m.source, a.parent)), m.suspendedBy, m.rejectedReason, m.since.orElse(Some(m.appliedAt)))
    membership(a.id, brand).call(Membership.importMembership).invoke(ImportMembership(state)): Unit

  private def link(brand: String, l: BundleLink): Unit =
    val ref = client.forKeyValueEntity(EntityId(l.id))
    if !ref.call(Link.get).invoke().exists then
      val tag = tagOf(l.affiliate, l.id, l.campaign, l.subIds, l.createdAt)
      ref.call(Link.createLink).invoke(CreateLink(l.id, l.affiliate, brand, l.campaign, l.landingPath, l.utm, l.subIds, Some(tag),
        l.legacyBtag, "import", l.createdAt)): Unit
    l.revokedAt.foreach(at => ref.call(Link.revoke).invoke(at): Unit)

  private def code(brand: String, b: Bundle, c: BundleCode): Unit =
    val ref = client.forKeyValueEntity(BonusCode.entityId(brand, c.code))
    if ref.call(BonusCode.get).invoke().status.isEmpty then
      val l   = b.links.find(_.id == c.link)
      val tag = tagOf(c.affiliate, c.link, l.flatMap(_.campaign), l.fold(Vector.empty[String])(_.subIds), c.createdAt)
      ref.call(BonusCode.claim).invoke(ClaimCode(c.code, brand, c.affiliate, c.link, c.offer, tag, c.createdAt)): Unit
      c.revokedAt.foreach(at => ref.call(BonusCode.revoke).invoke(RevokeCode(None, at)): Unit)

  private def invite(brand: String, i: BundleInvite): Unit =
    val ref = client.forKeyValueEntity(InviteLink.entityId(i.token))
    if !ref.call(InviteLink.get).invoke().exists then
      ref.call(InviteLink.createLink).invoke(CreateInviteLink(i.token, i.link, i.affiliate, brand, i.createdAt)): Unit
      i.revokedAt.foreach(at => ref.call(InviteLink.revoke).invoke(at): Unit)

  private def creative(brand: String, c: BundleCreative): Unit =
    val ref = client.forKeyValueEntity(EntityId(c.id))
    if !ref.call(Creative.get).invoke().exists then
      ref.call(Creative.write).invoke(WriteCreative(c.id, brand, c.name, c.creativeType, c.fileUrl, c.dimensions, c.description, c.by,
        c.createdAt)): Unit
      c.deletedAt.foreach(at => ref.call(Creative.delete).invoke(at): Unit)

  private def note(brand: String, n: BundleNote): Unit =
    client.forKeyValueEntity(EntityId(n.id)).call(Note.write).invoke(WriteNote(n.affiliate, brand, n.text, n.author, n.createdAt)): Unit

  private def audit(brand: String, a: BundleAudit): Unit =
    client.forKeyValueEntity(EntityId(a.id)).call(AuditEntry.write)
      .invoke(Audit(a.affiliate, Some(brand), a.action, a.actor, a.at, a.before, a.after, a.details)): Unit

  private def flag(brand: String, f: BundleFlag): Unit =
    client.forKeyValueEntity(FraudFlag.entityId(brand, f.id)).call(FraudFlag.restore)
      .invoke(RestoreFlag(FlagState(f.id, brand, f.pseudonym, f.affiliate, f.reason, f.description, f.period, Some(f.raisedAt), f.dismissedBy,
        f.dismissedAt, f.dismissalReason))): Unit

  /** The binding, and the first deposit's guard, so a later first deposit is not paid twice (FR-013). */
  private def player(brand: String, p: BundlePlayer): Unit =
    val ref   = client.forEventSourcedEntity(PlayerAttribution.entityId(brand, p.pseudonym))
    val known = ref.call(PlayerAttribution.get).invoke()
    def apply(kind: String, at: Instant, currency: Option[String], amount: Long) =
      val f = PlayerFact(kind, s"import/$kind/${p.pseudonym}", at, brand, p.pseudonym, p.affiliate, p.link, p.campaign, p.subIds, p.clickId,
        p.country, currency, amount)
      ref.call(PlayerAttribution.applyFact).invoke(ApplyFact(f, "", Some(0L), imported = true)): Unit
    if known.binding.isEmpty then apply(PlayerFact.Registration, p.registeredAt, None, 0L)
    if known.guard.isEmpty then p.firstDeposit.foreach(d => apply(PlayerFact.FirstDeposit, d.at, Some(d.currency), d.amount))

  private def ledger(brand: String, b: Bundle, key: (String, String)): Unit =
    val (affiliate, currency) = key
    val facts = b.facts.filter(f => f.affiliate == affiliate && f.currency == currency).map { f =>
      LedgerFact(f.id, FactType.parse(f.kind).get, f.amount, f.occurredAt, f.recordedAt, f.period, f.sourceAffiliate, f.settledBy)
    }
    val paid = b.payouts.collect {
      case p if p.affiliate == affiliate && p.currency == currency && p.status == Payout.Paid => Payout.idOf(affiliate, brand, currency, p.period)
    }.toSet
    client.forEventSourcedEntity(CommissionAccount.entityId(affiliate, brand, currency)).call(CommissionAccount.importFacts)
      .invoke(ImportFacts(facts, paid)): Unit

  private def payout(brand: String, p: BundlePayout): Unit =
    val state = PayoutState(p.affiliate, brand, p.currency, p.period, Some(p.status),
      Claim.Figures(p.cpa, p.revShare, p.overrideAmount, p.adjustments), p.approvedBy, p.approvedAt, p.rejectedBy, p.rejectedReason,
      p.paidBy, p.paidAt, openedAt = Some(p.createdAt))
    client.forEventSourcedEntity(Payout.entityId(p.affiliate, brand, p.currency, p.period)).call(Payout.importPayout)
      .invoke(ImportPayout(state)): Unit

final class Import(context: WorkflowContext) extends Workflow[ImportState]:
  def emptyState: ImportState = ImportState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(2.minutes).stepRecovery(Import.run, RecoverStrategy.maxRetries(5)).build

  /** Started once; the same bundle again answers the import as it stands. */
  def start(c: StartImport): Effect[ImportState] =
    if currentState.started then effects.reply(currentState)
    else
      effects.updateState(ImportState(c.brand, Some(c.bundle), c.accounts, c.by, Some(c.at)))
        .transitionTo(Import.run.ref).thenReplyState

  def runStep: StepEffect =
    val s        = currentState
    val sections = Sections(context.componentClient, context.secrets).of(s)
    if s.section >= sections.size then
      stepEffects.updateState(s.copy(bundle = None, accounts = Vector.empty, finishedAt = Some(Import.clock()))).thenEnd
    else
      val section = sections(s.section)
      val until   = math.min(section.size, s.cursor + Import.Batch)
      (s.cursor until until).foreach(section.write)
      val counts = s.counts.updated(section.name, until)
      val next   = if until >= section.size then s.copy(section = s.section + 1, cursor = 0) else s.copy(cursor = until)
      stepEffects.updateState(next.copy(counts = counts)).thenTransitionTo(Import.run.ref)

  def get: ReadOnlyEffect[ImportState] = effects.reply(currentState)

object Import extends Workflow.Companion[Import, ImportState](
      componentId = ComponentId("import"),
      stateSerializer = ProgrammeCodecs.serializer[ImportState]("import")
    ):
  /** How many items a step writes before it records where it is. */
  val Batch = 200

  final case class Section(name: String, size: Int, write: Int => Unit)

  /** The commission accounts a bundle's facts fall in, `(affiliate, currency)`, in a stable order. */
  def ledgers(b: Bundle): Vector[(String, String)] = b.facts.map(f => (f.affiliate, f.currency)).distinct.sorted

  private[imports] def clock(): Instant = io.eitheror.casino.programme.Wiring.clock.get().instant()

  def entityId(brand: String, digest: String): EntityId = EntityId(s"$brand/$digest")

  given Serializer[StartImport] = ProgrammeCodecs.serializer[StartImport]("start-import")

  def create(context: WorkflowContext) = new Import(context)

  val run = step("run")(_.runStep)

  val start = command("start")(_.start)
  val get   = query("get")(_.get)
