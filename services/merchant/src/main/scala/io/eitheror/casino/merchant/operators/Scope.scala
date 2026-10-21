package io.eitheror.casino.merchant.operators

/**
 * An operator's level in the payments hierarchy (decision 0050; the original's `ScopeLevel`, without
 * `Affiliate`, which no operator holds). Ordinal order is load-bearing: a lower ordinal is broader.
 */
enum Level(val claim: String):
  case Platform extends Level("platform")
  case Holding  extends Level("holding")
  case Company  extends Level("company")
  case SubBrand extends Level("sub_brand")

object Level:
  def from(s: String): Option[Level] = values.find(_.claim == s)

/** Where an operator stands: a level and, below the platform, the id of the entity at that level. */
final case class Scope(level: Level, id: String)

object Scope:
  val PlatformScope: Scope = Scope(Level.Platform, "*")

/**
 * What an operator may reach (FR-002, FR-029; the original's `HierarchicalAccess.permitted`), checked
 * against the hierarchy this service keeps rather than ancestors a token carries: a holding, company or
 * sub-brand is named with its parents. The platform reaches everything, a sub-brand with no parent
 * included; a holding operator its holding and below; a company operator its company and its
 * sub-brands; a sub-brand operator that sub-brand alone.
 */
final case class Target(holdingId: Option[String], companyId: Option[String] = None, subBrandId: Option[String] = None):
  /** The ids above and including the target, as an operator created at it records its ancestors. */
  def ancestors: Set[String] = (holdingId ++ companyId).toSet

object Access:
  def permits(scope: Scope, target: Target): Boolean =
    scope.level match
      case Level.Platform => true
      case Level.Holding  => target.holdingId.contains(scope.id)
      case Level.Company  => target.companyId.contains(scope.id)
      case Level.SubBrand => target.subBrandId.contains(scope.id)

  /** The original's refusal text (`CashierAdminRoutes.scala:243-245`). */
  val ScopeViolationMessage = "Your operator scope does not include this resource"
