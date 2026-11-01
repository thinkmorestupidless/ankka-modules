package io.eitheror.casino.rewards.offers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.Json

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * An offer's body in the routes' camelCase names (contracts/routes.md, data-model.md), read and written. Reading is
 * pure, so the endpoint and `OfferBook` read an edit alike. A field that cannot be read is refused naming it
 * (`invalid_body`); whether an amount is a whole number of minor units is the endpoint's check, before this
 * (FR-032). The bet-time rules are read on an edit only, as the original's.
 */
object Bodies:
  /** The money fields of an offer's body, each a whole number of minor units (FR-032). */
  val AmountFields: Vector[String] = Vector("maxBonusValue", "minDepositAmount", "maxConvertibleAmount", "spinValue", "maxStake")

  /** The deposit number target of none: any deposit. */
  val AnyDeposit = "ANY"

  private type Read[A] = Either[String, A]

  private def present(b: JsonNode, field: String): Boolean = b.has(field)
  private def isNull(b: JsonNode, field: String): Boolean  = Option(b.get(field)).exists(_.isNull)

  /** A field named: `None` absent, `Some(None)` cleared with `null`, `Some(Some(v))` set. */
  private def named[A](b: JsonNode, field: String)(read: JsonNode => Option[A]): Read[Option[Option[A]]] =
    if !present(b, field) then Right(None)
    else if isNull(b, field) then Right(Some(None))
    else read(b.get(field)).map(v => Some(Some(v))).toRight(field)

  private def text(n: JsonNode): Option[String]   = Option.when(n.isTextual || n.isNumber)(n.asText.trim)
  private def long(n: JsonNode): Option[Long]     =
    if n.isIntegralNumber && n.canConvertToLong then Some(n.asLong) else if n.isTextual then n.asText.trim.toLongOption else None
  private def int(n: JsonNode): Option[Int]       = long(n).filter(v => v.isValidInt).map(_.toInt)
  private def bool(n: JsonNode): Option[Boolean]  =
    if n.isBoolean then Some(n.asBoolean) else if n.isTextual then n.asText.trim.toLowerCase.toBooleanOption else None
  private def instant(n: JsonNode): Option[Instant] = text(n).flatMap(t => Try(Instant.parse(t)).toOption)
  private def currency(n: JsonNode): Option[Currency] = text(n).flatMap(t => Currency.from(t.toUpperCase).toOption)
  private def strings(n: JsonNode): Option[Set[String]] =
    Option.when(n.isArray)(n.elements.asScala.flatMap(text).filter(_.nonEmpty).toSet)

  /** `"ANY"` (or nothing) is any deposit; a number is that deposit. */
  private def depositNumber(n: JsonNode): Option[Option[Int]] =
    text(n) match
      case Some(t) if t.equalsIgnoreCase(AnyDeposit) || t.isEmpty => Some(None)
      case Some(t)                                          => t.toIntOption.map(Some(_))
      case None                                             => None

  private def codes(n: JsonNode): Option[Vector[RedemptionCode]] =
    Option.when(n.isArray)(n.elements.asScala.toVector.flatMap { c =>
      if c.isTextual then Some(RedemptionCode(c.asText))
      else Option(c.get("code")).flatMap(text).map(code => RedemptionCode(code, Option(c.get("isAffiliate")).flatMap(bool).getOrElse(false)))
    })

  private def spinEntries(n: JsonNode): Option[Vector[SpinEntry]] =
    Option.when(n.isArray)(n.elements.asScala.toVector).flatMap { entries =>
      val read = entries.map(e => for c <- Option(e.get("currency")).flatMap(text); v <- Option(e.get("spinValue")).flatMap(long) yield SpinEntry(c, v))
      Option.when(read.forall(_.isDefined))(read.flatten)
    }

  private def overrides(n: JsonNode): Option[Map[String, Long]] =
    Option.when(n.isObject)(n.properties.asScala.toVector.map(e => e.getKey -> long(e.getValue))).flatMap { kvs =>
      Option.when(kvs.forall(_._2.isDefined))(kvs.map((k, v) => k -> v.get).toMap)
    }

  /** The type: absent is `DEPOSIT_MATCH` (the original stores what it is sent and treats anything not free rounds as a match). */
  private def offerType(b: JsonNode): Read[OfferType] =
    named(b, "type")(n => text(n).flatMap(OfferType.from)).map(_.flatten.getOrElse(OfferType.DepositMatch))

  /** A create's body as a draft, with the id and the times the endpoint gives it. */
  def draft(b: JsonNode, id: String, now: Instant): Either[String, Draft] =
    def opt[A](field: String)(read: JsonNode => Option[A]): Read[Option[A]] = named(b, field)(read).map(_.flatten)
    for
      t           <- offerType(b)
      name        <- opt("name")(text)
      description <- opt("description")(text)
      payload     <- opt("payloadType")(text)
      validFrom   <- opt("validFrom")(instant)
      validUntil  <- opt("validUntil")(instant)
      percentage  <- opt("bonusPercentage")(long)
      maxValue    <- opt("maxBonusValue")(long)
      minDeposit  <- opt("minDepositAmount")(long)
      maxConvert  <- opt("maxConvertibleAmount")(long)
      number      <- named(b, "depositNumberTarget")(depositNumber).map(_.flatten.flatten)
      claimDays   <- opt("claimWindowDays")(int)
      wagerDays   <- opt("wageringWindowDays")(int)
      spinDays    <- opt("freeSpinsExpiryDays")(int)
      multiplier  <- opt("wageringMultiplier")(long)
      maxClaims   <- opt("maxClaimsPerPlayer")(int)
      concurrent  <- opt("maxConcurrentGrants")(int)
      excluded    <- opt("excludedOfferIds")(strings)
      conflicts   <- opt("conflictsWith")(strings)
      codesIn     <- opt("redemptionCodes")(codes)
      segment     <- opt("segmentPredicate")(text)
      tier        <- opt("vipTierFloor")(int)
      role        <- opt("role")(text)
      noDeposit   <- opt("noDepositRequired")(bool)
      cur         <- opt("currency")(currency)
      priority    <- opt("priority")(long)
      template    <- opt("notificationTemplate")(text)
      fieldMap    <- opt("fieldMap")(text)
      gameId      <- opt("gameId")(text)
      gameName    <- opt("gameName")(text)
      provider    <- opt("provider")(text)
      spinCount   <- opt("spinCount")(int)
      spinValue   <- opt("spinValue")(long)
      configs     <- opt("spinConfigurations")(spinEntries)
      destination <- opt("winningsDestination")(n => text(n).flatMap(WinningsDestination.from))
    yield Draft(
      Offer(id = id, name = name.getOrElse(""), description = description.getOrElse(""), offerType = t, validFrom = validFrom,
        validUntil = validUntil, bonusPercentage = percentage.getOrElse(0L), maxBonusValue = maxValue, minDepositAmount = minDeposit,
        maxConvertibleAmount = maxConvert, depositNumberTarget = number, claimWindowDays = claimDays, wageringWindowDays = wagerDays,
        freeSpinsExpiryDays = spinDays, wageringMultiplier = multiplier.getOrElse(0L), maxClaimsPerPlayer = maxClaims,
        maxConcurrentGrants = concurrent, excludedOfferIds = excluded.getOrElse(Set.empty), conflictsWith = conflicts.getOrElse(Set.empty),
        redemptionCodes = codesIn.getOrElse(Vector.empty), segmentPredicate = segment.filter(_.nonEmpty), vipTierFloor = tier,
        role = role.filter(_.nonEmpty), noDepositRequired = noDeposit.getOrElse(false), currency = cur, priority = priority,
        notificationTemplate = template, fieldMap = fieldMap, gameId = gameId.filter(_.nonEmpty), gameName = gameName,
        provider = provider.filter(_.nonEmpty), spinCount = spinCount, spinValue = spinValue, winningsDestination = destination,
        createdAt = now, updatedAt = now),
      payloadType = payload,
      spinConfigurations = configs
    )

  /** An edit's body against the offer it edits: the bet-time rules named are laid over the offer's own. */
  def patch(b: JsonNode, existing: Offer): Either[String, Patch] =
    def req[A](field: String)(read: JsonNode => Option[A]): Read[Option[A]] = named(b, field)(read).map(_.flatten)
    val rules = existing.betRules
    for
      t           <- named(b, "type")(n => text(n).flatMap(OfferType.from)).map(_.flatten)
      name        <- req("name")(text)
      description <- req("description")(text)
      payload     <- req("payloadType")(text)
      validFrom   <- named(b, "validFrom")(instant)
      validUntil  <- named(b, "validUntil")(instant)
      percentage  <- req("bonusPercentage")(long)
      maxValue    <- named(b, "maxBonusValue")(long)
      minDeposit  <- named(b, "minDepositAmount")(long)
      maxConvert  <- named(b, "maxConvertibleAmount")(long)
      number      <- named(b, "depositNumberTarget")(depositNumber).map(_.map(_.flatten))
      claimDays   <- named(b, "claimWindowDays")(int)
      wagerDays   <- named(b, "wageringWindowDays")(int)
      spinDays    <- named(b, "freeSpinsExpiryDays")(int)
      multiplier  <- req("wageringMultiplier")(long)
      maxClaims   <- named(b, "maxClaimsPerPlayer")(int)
      concurrent  <- named(b, "maxConcurrentGrants")(int)
      excluded    <- req("excludedOfferIds")(strings)
      conflicts   <- req("conflictsWith")(strings)
      codesIn     <- req("redemptionCodes")(codes)
      segment     <- named(b, "segmentPredicate")(text)
      tier        <- named(b, "vipTierFloor")(int)
      role        <- named(b, "role")(text)
      noDeposit   <- req("noDepositRequired")(bool)
      cur         <- named(b, "currency")(currency)
      priority    <- named(b, "priority")(long)
      template    <- named(b, "notificationTemplate")(text)
      fieldMap    <- named(b, "fieldMap")(text)
      gameId      <- named(b, "gameId")(text)
      gameName    <- named(b, "gameName")(text)
      provider    <- named(b, "provider")(text)
      spinCount   <- named(b, "spinCount")(int)
      spinValue   <- named(b, "spinValue")(long)
      configs     <- req("spinConfigurations")(spinEntries)
      destination <- named(b, "winningsDestination")(n => text(n).flatMap(WinningsDestination.from))
      maxStake    <- named(b, "maxStake")(long)
      enforcement <- named(b, "maxStakeEnforcement")(text)
      contribution <- named(b, "contributionDefault")(long)
      overridesIn <- req("contributionOverrides")(overrides)
      include     <- req("scopeInclude")(strings)
      exclude     <- req("scopeExclude")(strings)
      violation   <- named(b, "scopeViolationMode")(text)
      funding     <- named(b, "fundingOrder")(text)
      allocation  <- named(b, "winAllocation")(text)
      onWithdraw  <- named(b, "forfeitOnWithdrawal")(text)
      onCancel    <- named(b, "forfeitOnCancel")(text)
    yield
      val betNamed = Vector(maxStake, enforcement, contribution, violation, funding, allocation, onWithdraw, onCancel).exists(_.isDefined) ||
        overridesIn.isDefined || include.isDefined || exclude.isDefined
      Patch(name = name, description = description, offerType = t, payloadType = payload, validFrom = validFrom,
        validUntil = validUntil, bonusPercentage = percentage, maxBonusValue = maxValue, minDepositAmount = minDeposit,
        maxConvertibleAmount = maxConvert, depositNumberTarget = number, claimWindowDays = claimDays, wageringWindowDays = wagerDays,
        freeSpinsExpiryDays = spinDays, wageringMultiplier = multiplier, maxClaimsPerPlayer = maxClaims,
        maxConcurrentGrants = concurrent, excludedOfferIds = excluded, conflictsWith = conflicts, redemptionCodes = codesIn,
        segmentPredicate = segment, vipTierFloor = tier, role = role, noDepositRequired = noDeposit, currency = cur, priority = priority,
        notificationTemplate = template, fieldMap = fieldMap, gameId = gameId, gameName = gameName, provider = provider,
        spinCount = spinCount, spinValue = spinValue, spinConfigurations = configs, winningsDestination = destination,
        betRules = Option.when(betNamed)(rules.copy(
          maxStake = maxStake.getOrElse(rules.maxStake), maxStakeEnforcement = enforcement.getOrElse(rules.maxStakeEnforcement),
          contributionDefault = contribution.getOrElse(rules.contributionDefault),
          contributionOverrides = overridesIn.getOrElse(rules.contributionOverrides), scopeInclude = include.getOrElse(rules.scopeInclude),
          scopeExclude = exclude.getOrElse(rules.scopeExclude), scopeViolationMode = violation.getOrElse(rules.scopeViolationMode),
          fundingOrder = funding.getOrElse(rules.fundingOrder), winAllocation = allocation.getOrElse(rules.winAllocation),
          forfeitOnWithdrawal = onWithdraw.getOrElse(rules.forfeitOnWithdrawal), forfeitOnCancel = onCancel.getOrElse(rules.forfeitOnCancel))))

  /** The fields a body names, for the edit's audit record. */
  def namedFields(b: JsonNode): Vector[String] = b.properties.asScala.toVector.map(_.getKey).sorted

  /** An offer as the routes answer it: every field in the routes' names, amounts in minor units. */
  def render(o: Offer): ObjectNode =
    val r = o.betRules
    Json.obj(
      "id" -> o.id, "name" -> o.name, "description" -> o.description, "type" -> o.offerType.wire, "payloadType" -> o.payload.wire,
      "active" -> o.active, "validFrom" -> o.validFrom.map(_.toString), "validUntil" -> o.validUntil.map(_.toString),
      "bonusPercentage" -> o.bonusPercentage, "maxBonusValue" -> o.maxBonusValue, "minDepositAmount" -> o.minDepositAmount,
      "maxConvertibleAmount" -> o.maxConvertibleAmount, "depositNumberTarget" -> o.depositNumberTarget.fold[Any](AnyDeposit)(identity),
      "claimWindowDays" -> o.claimWindowDays, "wageringWindowDays" -> o.wageringWindowDays,
      "freeSpinsExpiryDays" -> o.freeSpinsExpiryDays, "wageringMultiplier" -> o.wageringMultiplier,
      "maxClaimsPerPlayer" -> o.maxClaimsPerPlayer, "maxConcurrentGrants" -> o.maxConcurrentGrants,
      "excludedOfferIds" -> o.excludedOfferIds.toVector.sorted, "conflictsWith" -> o.conflictsWith.toVector.sorted,
      "redemptionCodes" -> Json.arr(o.redemptionCodes.map(c => Json.obj("code" -> c.code, "isAffiliate" -> c.affiliate))*),
      "segmentPredicate" -> o.segmentPredicate, "vipTierFloor" -> o.vipTierFloor, "role" -> o.role,
      "noDepositRequired" -> o.noDepositRequired, "currency" -> o.currency.map(_.code), "priority" -> o.priority,
      "notificationTemplate" -> o.notificationTemplate, "fieldMap" -> o.fieldMap, "gameId" -> o.gameId, "gameName" -> o.gameName,
      "provider" -> o.provider, "spinCount" -> o.spinCount, "spinValue" -> o.spinValue,
      "spinConfigurations" -> Json.arr(o.spinConfigurations.toVector.sortBy(_._1.code).map((c, v) => Json.obj("currency" -> c.code,
        "spinValue" -> v))*),
      "winningsDestination" -> o.winningsDestination.map(_.wire), "maxStake" -> r.maxStake, "maxStakeEnforcement" -> r.maxStakeEnforcement,
      "contributionDefault" -> r.contributionDefault,
      "contributionOverrides" -> Json.obj(r.contributionOverrides.toVector.sortBy(_._1)*),
      "scopeInclude" -> r.scopeInclude.toVector.sorted, "scopeExclude" -> r.scopeExclude.toVector.sorted,
      "scopeViolationMode" -> r.scopeViolationMode, "fundingOrder" -> r.fundingOrder, "winAllocation" -> r.winAllocation,
      "forfeitOnWithdrawal" -> r.forfeitOnWithdrawal, "forfeitOnCancel" -> r.forfeitOnCancel,
      "createdAt" -> o.createdAt.toString, "updatedAt" -> o.updatedAt.toString
    )
