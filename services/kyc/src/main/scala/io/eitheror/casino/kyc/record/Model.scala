package io.eitheror.casino.kyc.record

/**
 * A document's category (data-model.md). `Identity` is the legacy category of documents uploaded before
 * identity was split into front and back: it is read from the migration only, and FR-013 lets an approved
 * one stand for the front while no front exists.
 */
enum Category(val wire: String):
  case IdentityFront      extends Category("identity_front")
  case IdentityBack       extends Category("identity_back")
  case ProofOfAddress     extends Category("proof_of_address")
  case PaymentMethod      extends Category("payment_method")
  case BankAccountDetails extends Category("bank_account_details")
  case Other              extends Category("other")
  case Identity           extends Category("identity")

object Category:
  def of(wire: String): Option[Category] = values.find(_.wire == wire)

/** The account's verification status (FR-012). */
enum Status(val wire: String):
  case Unverified extends Status("unverified")
  case Pending    extends Status("pending")
  case Verified   extends Status("verified")
  case Rejected   extends Status("rejected")

object Status:
  def of(wire: String): Option[Status] = values.find(_.wire == wire)

/** Who decides that the account is verified (FR-015). */
enum Mode(val wire: String):
  case Automatic extends Mode("automatic")
  case Manual    extends Mode("manual")

/** Why a player is flagged for review (FR-024). */
enum FlagReason(val wire: String):
  case Threshold     extends FlagReason("threshold")
  case SourceOfFunds extends FlagReason("source_of_funds")
  case Window        extends FlagReason("window")
  case LargeDeposit  extends FlagReason("large_deposit")

/** The source of funds review's status. */
enum Sof(val wire: String):
  case Unverified extends Sof("unverified")
  case Cleared    extends Sof("cleared")

/** A review's outcome; a document with none is awaiting review. */
enum Outcome(val wire: String):
  case Approved extends Outcome("approved")
  case Rejected extends Outcome("rejected")
