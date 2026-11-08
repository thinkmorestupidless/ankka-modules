package io.eitheror.casino.rewards.offers

import io.eitheror.casino.contract.money.Currency

import java.time.Instant

/** A create or an edit refused: the status, the `error` code, and the fields beside it (contracts/routes.md). */
final case class Refusal(status: Int, error: String, detail: Vector[(String, String)] = Vector.empty)

object Refusal:
  private def bad(error: String) = Refusal(400, error)

  val BonusAmountRequired          = bad("bonus_amount_required")
  val WageringMultiplierRequired   = bad("wagering_multiplier_required")
  val InvalidPayloadType           = bad("invalid_payload_type")
  val MaxConcurrentGrantsInvalid   = bad("max_concurrent_grants_invalid")
  val CurrencyRequired             = bad("currency_required")
  val GameIdRequired               = bad("game_id_required")
  val SpinCountRequired            = bad("spin_count_required")
  val SpinValueRequired            = bad("spin_value_required")
  val MaxConvertibleAmountRequired = bad("max_convertible_amount_required")
  val DuplicateCodeInRequest       = Refusal(409, "duplicate_code_in_request")
  val RoleAlreadyAssigned          = Refusal(409, "role_already_assigned")
  val OfferNotFound                = Refusal(404, "offer_not_found")

  def spinConfigurationsInvalid(reason: String): Refusal = Refusal(400, "spin_configurations_invalid", Vector("reason" -> reason))
  def codeAlreadyInUse(code: String): Refusal            = Refusal(409, "code_already_in_use", Vector("code" -> code))

/** One `spinConfigurations` entry as the body gave it, its amount already whole minor units (FR-032). */
final case class SpinEntry(currency: String, spinValue: Long)

/**
 * A create's body, read: the offer as given (its `payloadType` and `spinConfigurations` are replaced by
 * the two raw fields here, which the checks read), with the id and the times the endpoint gave it.
 */
final case class Draft(offer: Offer, payloadType: Option[String] = None, spinConfigurations: Option[Vector[SpinEntry]] = None)

/**
 * An edit's body: each field the body names is `Some`, its new value; `Some(None)` clears an optional one.
 * `payloadType` and `spinConfigurations` are raw, as in `Draft`.
 */
final case class Patch(
    name: Option[String] = None,
    description: Option[String] = None,
    offerType: Option[OfferType] = None,
    payloadType: Option[String] = None,
    validFrom: Option[Option[Instant]] = None,
    validUntil: Option[Option[Instant]] = None,
    bonusPercentage: Option[Long] = None,
    maxBonusValue: Option[Option[Long]] = None,
    minDepositAmount: Option[Option[Long]] = None,
    maxConvertibleAmount: Option[Option[Long]] = None,
    depositNumberTarget: Option[Option[Int]] = None,
    claimWindowDays: Option[Option[Int]] = None,
    wageringWindowDays: Option[Option[Int]] = None,
    freeSpinsExpiryDays: Option[Option[Int]] = None,
    wageringMultiplier: Option[Long] = None,
    maxClaimsPerPlayer: Option[Option[Int]] = None,
    maxConcurrentGrants: Option[Option[Int]] = None,
    excludedOfferIds: Option[Set[String]] = None,
    conflictsWith: Option[Set[String]] = None,
    redemptionCodes: Option[Vector[RedemptionCode]] = None,
    segmentPredicate: Option[Option[String]] = None,
    vipTierFloor: Option[Option[Int]] = None,
    role: Option[Option[String]] = None,
    noDepositRequired: Option[Boolean] = None,
    currency: Option[Option[Currency]] = None,
    priority: Option[Option[Long]] = None,
    notificationTemplate: Option[Option[String]] = None,
    fieldMap: Option[Option[String]] = None,
    gameId: Option[Option[String]] = None,
    gameName: Option[Option[String]] = None,
    provider: Option[Option[String]] = None,
    spinCount: Option[Option[Int]] = None,
    spinValue: Option[Option[Long]] = None,
    spinConfigurations: Option[Vector[SpinEntry]] = None,
    winningsDestination: Option[Option[WinningsDestination]] = None,
    betRules: Option[BetRules] = None
):
  /** The fields the spin-value check depends on (the original's `touchesSpinRelevantFields`). */
  def touchesSpins: Boolean = gameId.isDefined || spinValue.isDefined || currency.isDefined || spinConfigurations.isDefined

/** A spin value the provider must be asked about for a game and currency (FR-002 `spin_value_not_offered`). */
final case class SpinCheck(gameId: String, currency: Currency, spinValue: Long)

/**
 * The create and edit checks (FR-002, FR-003; data-model.md's order), the first refusal answered. The
 * provider's spin-value and game checks are the endpoint's, before these (`spinValueChecks` says what to
 * ask). Code uniqueness is against the brand's other active offers, as the original's, and the programme's
 * document, both in `Codes.normalise`'s form.
 */
object OfferRules:

  def create(draft: Draft, book: Iterable[Offer], programmeCodes: Set[String]): Either[Refusal, Offer] =
    val o = draft.offer
    // A pure free-rounds offer has no deposit-match amount; one paying real balance has nothing to wager (BR:1897-1920).
    val pureRounds = o.offerType == OfferType.FreeSpins
    for
      _       <- Either.cond(pureRounds || o.maxBonusValue.exists(_ > 0), (), Refusal.BonusAmountRequired)
      _       <- Either.cond((pureRounds && o.destination == WinningsDestination.RealBalance) || o.wageringMultiplier > 0, (),
                   Refusal.WageringMultiplierRequired)
      payload <- payloadOf(draft.payloadType)
      _       <- concurrent(o.maxConcurrentGrants)
      configs <- spinConfigurations(draft.spinConfigurations).map(_.getOrElse(Map.empty))
      offer    = withSpins(o.copy(payloadType = payload, active = true, redemptionCodes = codesOf(o.redemptionCodes)), configs)
      _       <- freeRoundsFields(offer)
      _       <- codes(offer.redemptionCodes, Codes.taken(book, offer.id), programmeCodes)
    yield offer

  def edit(id: String, patch: Patch, book: Iterable[Offer], programmeCodes: Set[String], now: Instant): Either[Refusal, Offer] =
    for
      existing <- book.find(_.id == id).toRight(Refusal.OfferNotFound)
      payload  <- payloadOf(patch.payloadType)
      _        <- concurrent(patch.maxConcurrentGrants.flatten)
      configs  <- spinConfigurations(patch.spinConfigurations)
      edited    = applied(existing, patch, payload, configs, now)
      // A free-rounds offer an edit of its spin fields leaves with nothing to grant (BR:2272-2290).
      _        <- Either.cond(!(patch.touchesSpins && edited.freeRounds && !hasSpinValue(edited)), (), Refusal.SpinValueRequired)
      _        <- role(edited, patch, book)
      _        <- patch.redemptionCodes.fold(Right(())) { _ =>
                    val own = existing.redemptionCodes.map(c => Codes.normalise(c.code)).toSet
                    val added = edited.redemptionCodes.filterNot(c => own.contains(Codes.normalise(c.code)))
                    if Codes.repeats(edited.redemptionCodes) then Left(Refusal.DuplicateCodeInRequest)
                    else codes(added, Codes.taken(book, id), programmeCodes)
                  }
    yield edited

  /**
   * The spin values the provider must be asked about: every currency of a free-rounds offer, or after an
   * edit only those whose game, currency or value changed (the original's 237: an unchanged triple the
   * provider no longer lists must not block editing the rest).
   */
  def spinValueChecks(offer: Offer, before: Option[Offer] = None): Vector[SpinCheck] =
    if !offer.freeRounds then Vector.empty
    else
      val checks = for
        game          <- offer.gameId.toVector
        (currency, v) <- triples(offer)
      yield SpinCheck(game, currency, v)
      before.fold(checks) { b =>
        val old = b.gameId.toVector.flatMap(g => triples(b).map((c, v) => SpinCheck(g, c, v))).toSet
        checks.filterNot(old.contains)
      }

  private def triples(o: Offer): Vector[(Currency, Long)] =
    if o.spinConfigurations.nonEmpty then o.spinConfigurations.toVector.sortBy(_._1.code)
    else (for c <- o.currency; v <- o.spinValue yield (c, v)).toVector

  private def payloadOf(raw: Option[String]): Either[Refusal, Option[PayloadType]] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None       => Right(None)
      case Some(text) => PayloadType.from(text).map(Some(_)).toRight(Refusal.InvalidPayloadType)

  private def concurrent(n: Option[Int]): Either[Refusal, Unit] =
    Either.cond(n.forall(_ >= 1), (), Refusal.MaxConcurrentGrantsInvalid)

  /** The original's `serialiseSpinConfigurations`: trimmed, upper-cased ISO codes, positive values, each once. */
  private def spinConfigurations(raw: Option[Vector[SpinEntry]]): Either[Refusal, Option[Map[Currency, Long]]] =
    raw match
      case None => Right(None)
      case Some(entries) =>
        val parsed = entries.map { e =>
          Currency.from(e.currency.trim.toUpperCase).left.map(_ => "invalid_currency")
            .flatMap(c => Either.cond(e.spinValue > 0, c -> e.spinValue, s"invalid_spin_value:${c.code}"))
        }
        parsed.collectFirst { case Left(reason) => reason } match
          case Some(reason) => Left(Refusal.spinConfigurationsInvalid(reason))
          case None =>
            val ok = parsed.collect { case Right(e) => e }
            ok.groupBy(_._1).collectFirst { case (c, es) if es.size > 1 => c } match
              case Some(twice) => Left(Refusal.spinConfigurationsInvalid(s"duplicate_currency:${twice.code}"))
              case None        => Right(Some(ok.toMap))

  /** Configurations with entries own the spin value: the flat pair is cleared (data-model.md). */
  private def withSpins(o: Offer, configs: Map[Currency, Long]): Offer =
    if configs.isEmpty then o.copy(spinConfigurations = Map.empty)
    else o.copy(spinConfigurations = configs, currency = None, spinValue = None)

  private def hasSpinValue(o: Offer): Boolean =
    o.spinConfigurations.nonEmpty || (o.currency.isDefined && o.spinValue.exists(_ > 0))

  private def freeRoundsFields(o: Offer): Either[Refusal, Unit] =
    if !o.freeRounds then Right(())
    else
      for
        _ <- Either.cond(o.currency.isDefined || o.spinConfigurations.nonEmpty, (), Refusal.CurrencyRequired)
        _ <- Either.cond(o.gameId.exists(_.trim.nonEmpty), (), Refusal.GameIdRequired)
        _ <- Either.cond(o.spinCount.exists(_ > 0), (), Refusal.SpinCountRequired)
        _ <- Either.cond(o.spinConfigurations.nonEmpty || o.spinValue.exists(_ > 0), (), Refusal.SpinValueRequired)
        _ <- Either.cond(o.destination != WinningsDestination.BonusBalance || o.maxConvertibleAmount.exists(_ > 0), (),
               Refusal.MaxConvertibleAmountRequired)
      yield ()

  private def codesOf(codes: Vector[RedemptionCode]): Vector[RedemptionCode] = codes.filter(_.code.trim.nonEmpty)

  private def codes(codes: Vector[RedemptionCode], taken: Map[String, String], programme: Set[String]): Either[Refusal, Unit] =
    if Codes.repeats(codes) then Left(Refusal.DuplicateCodeInRequest)
    else Codes.firstInUse(codes, taken, programme).map(Refusal.codeAlreadyInUse).toLeft(())

  /** At most one active offer of the brand holds a role (BR:290-310); clearing it never conflicts. */
  private def role(edited: Offer, patch: Patch, book: Iterable[Offer]): Either[Refusal, Unit] =
    patch.role.flatten.filter(_.trim.nonEmpty) match
      case None    => Right(())
      case Some(r) => Either.cond(!book.exists(o => o.id != edited.id && o.active && o.role.contains(r)), (), Refusal.RoleAlreadyAssigned)

  private def applied(o: Offer, p: Patch, payload: Option[PayloadType], configs: Option[Map[Currency, Long]], now: Instant): Offer =
    val fields = o.copy(
      name = p.name.getOrElse(o.name),
      description = p.description.getOrElse(o.description),
      offerType = p.offerType.getOrElse(o.offerType),
      payloadType = payload.orElse(o.payloadType),
      validFrom = p.validFrom.getOrElse(o.validFrom),
      validUntil = p.validUntil.getOrElse(o.validUntil),
      bonusPercentage = p.bonusPercentage.getOrElse(o.bonusPercentage),
      maxBonusValue = p.maxBonusValue.getOrElse(o.maxBonusValue),
      minDepositAmount = p.minDepositAmount.getOrElse(o.minDepositAmount),
      maxConvertibleAmount = p.maxConvertibleAmount.getOrElse(o.maxConvertibleAmount),
      depositNumberTarget = p.depositNumberTarget.getOrElse(o.depositNumberTarget),
      claimWindowDays = p.claimWindowDays.getOrElse(o.claimWindowDays),
      wageringWindowDays = p.wageringWindowDays.getOrElse(o.wageringWindowDays),
      freeSpinsExpiryDays = p.freeSpinsExpiryDays.getOrElse(o.freeSpinsExpiryDays),
      wageringMultiplier = p.wageringMultiplier.getOrElse(o.wageringMultiplier),
      maxClaimsPerPlayer = p.maxClaimsPerPlayer.getOrElse(o.maxClaimsPerPlayer),
      maxConcurrentGrants = p.maxConcurrentGrants.getOrElse(o.maxConcurrentGrants),
      excludedOfferIds = p.excludedOfferIds.getOrElse(o.excludedOfferIds),
      conflictsWith = p.conflictsWith.getOrElse(o.conflictsWith),
      redemptionCodes = p.redemptionCodes.map(codesOf).getOrElse(o.redemptionCodes),
      segmentPredicate = p.segmentPredicate.getOrElse(o.segmentPredicate),
      vipTierFloor = p.vipTierFloor.getOrElse(o.vipTierFloor),
      role = p.role.map(_.filter(_.trim.nonEmpty)).getOrElse(o.role),
      noDepositRequired = p.noDepositRequired.getOrElse(o.noDepositRequired),
      currency = p.currency.getOrElse(o.currency),
      priority = p.priority.getOrElse(o.priority),
      notificationTemplate = p.notificationTemplate.getOrElse(o.notificationTemplate),
      fieldMap = p.fieldMap.getOrElse(o.fieldMap),
      gameId = p.gameId.getOrElse(o.gameId),
      gameName = p.gameName.getOrElse(o.gameName),
      provider = p.provider.getOrElse(o.provider),
      spinCount = p.spinCount.getOrElse(o.spinCount),
      spinValue = p.spinValue.getOrElse(o.spinValue),
      winningsDestination = p.winningsDestination.getOrElse(o.winningsDestination),
      betRules = p.betRules.getOrElse(o.betRules),
      updatedAt = now
    )
    configs.fold(fields)(withSpins(fields, _))
