package io.eitheror.casino.contract

/**
 * The shape every brand and shared project is built into (spec 001 FR-001 to FR-003, FR-011,
 * FR-016). The brands tool validates manifests against it; services read it for names they share.
 * Changes here change spec 001 first.
 */
object Layout:

  /** The services of a brand project (FR-002), each specified by its own spec. */
  val BrandServices: Vector[String] = Vector(
    "wallet", "payouts", "players", "kyc", "responsible-gambling", "rewards", "game-provider",
    "attribution", "partner-api", "engagement", "reporting", "support-agent"
  )

  /** Brand services a manifest may leave out (FR-007: the pilot excludes partner-api; support-agent ships off). */
  val OptionalBrandServices: Set[String] = Set("partner-api", "support-agent")

  /** Brand services that get an object-storage bucket (research R12). */
  val ObjectStorageServices: Set[String] = Set("kyc", "reporting")

  /** The shared projects and their services (FR-003). */
  val SharedProjects: Map[String, Vector[String]] = Map(
    "payments"  -> Vector("merchant", "psp-gateway", "ingest"),
    "programme" -> Vector("affiliate-programme"),
    "reasoner"  -> Vector("audit")
  )

  /** Services whose every route is of class `service`: never exposed (contracts/routes.md). */
  val ServiceOnly: Set[String] = Set("merchant")

  /** Ids no brand may take: the shared projects and ankka's own reservations (FR-001, research R2). */
  val ReservedSlugs: Set[String] = SharedProjects.keySet ++ Set("platform", "local", "machine")

  /**
   * A slug is at most 42 characters: `<service>-<slug>` is one DNS label of at most 63, and the
   * longest brand service, `responsible-gambling`, takes 21 with its hyphen (research R2).
   */
  val MaxSlugLength: Int = 63 - BrandServices.map(_.length + 1).max

  private val SlugPattern = "[a-z][a-z0-9-]*".r

  def slugProblems(slug: String): Vector[String] =
    Vector(
      Option.when(!SlugPattern.matches(slug) || slug.endsWith("-"))(
        s"slug '$slug': lowercase letters, digits and '-', starting with a letter, not ending with '-'"
      ),
      Option.when(slug.length > MaxSlugLength)(
        s"slug '$slug' is ${slug.length} characters; a brand's slug is at most $MaxSlugLength"
      ),
      Option.when(ReservedSlugs.contains(slug))(s"slug '$slug' is reserved")
    ).flatten

  /** The ten topics of every brand project (FR-011). */
  val BrandTopics: Vector[String] = Vector(
    "money.transactions", "payments.payments", "casino.players", "casino.sessions", "casino.rounds",
    "casino.withdrawals", "casino.rewards", "affiliates.attribution", "casino.push", "platform.audit"
  )

  /** Topics of the shared projects that are part of the contract (FR-011). */
  val SharedTopics: Map[String, Vector[String]] = Map(
    "payments"  -> Vector("payments.merchants"),
    "programme" -> Vector.empty,
    "reasoner"  -> Vector.empty
  )

  /** Retention a topic is declared with (FR-015): facts are kept; two topics are ephemeral. */
  enum Retention:
    case Fact
    case Days(days: Int)

  val EphemeralTopics: Map[String, Retention] =
    Map("casino.sessions" -> Retention.Days(90), "casino.push" -> Retention.Days(1))

  def retentionOf(topic: String): Retention = EphemeralTopics.getOrElse(topic, Retention.Fact)

  /** What a grant opens (ankka 040's three kinds). */
  enum GrantTarget:
    case Route(service: String, method: String, path: String)
    case Topic(name: String, right: String)
    case Erasure

  final case class Grant(principal: String, target: GrantTarget)

  /** The grants every brand makes, and no others (FR-016, contracts/grants.md). */
  val BrandGrantsMade: Vector[Grant] = Vector(
    Grant("service:payments/merchant", GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/wallets/{player}/{currency}/deposits")),
    Grant("service:payments/merchant", GrantTarget.Route("wallet", "POST", "/v1/casino/brands/{brand}/payments/{paymentId}/outcome")),
    Grant("service:payments/psp-gateway", GrantTarget.Route("payouts", "POST", "/v1/casino/brands/{brand}/withdrawals/{requestId}/outcome")),
    Grant("service:programme/affiliate-programme", GrantTarget.Topic("affiliates.attribution", "consume")),
    Grant("service:reasoner/audit", GrantTarget.Topic("platform.audit", "consume"))
  )

  /** The grants a brand holds from `payments`, the principal naming the brand's own service. */
  def brandGrantsReceived(slug: String): Vector[Grant] = Vector(
    Grant(s"service:$slug/payouts", GrantTarget.Route("psp-gateway", "POST", "/v1/payments/withdrawals")),
    Grant(s"service:$slug/players", GrantTarget.Erasure)
  )

  /**
   * The shared services a brand's `service` routes admit by name. Before ankka 040 there is no
   * `Callers.granted`, so the brand's own grants are this list, and contract.access admits exactly
   * these callers on exactly the routes granted to them.
   */
  def grantedCaller(principal: String): Option[(String, String)] =
    principal.stripPrefix("service:").split('/') match
      case Array(project, service) if principal.startsWith("service:") => Some(project -> service)
      case _                                                           => None
