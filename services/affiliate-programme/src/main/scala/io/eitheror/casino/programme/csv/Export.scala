package io.eitheror.casino.programme.csv

import java.time.Instant

/**
 * The payment export (contracts/routes.md *The export*): one row per approved payout, amounts in minor
 * units. Built on the server, where reading the payment details can be recorded (FR-021, change 16).
 */
object Export:
  val Header: Vector[String] = Vector("affiliateId", "displayName", "email", "paymentMethod", "brand", "currency", "period",
    "cpa", "revShare", "override", "adjustments", "total", "approvedBy", "approvedAt")

  final case class Row(
      affiliateId: String,
      displayName: String,
      email: String,
      paymentMethod: String,
      brand: String,
      currency: String,
      period: String,
      cpa: Long,
      revShare: Long,
      overrideAmount: Long,
      adjustments: Long,
      total: Long,
      approvedBy: String,
      approvedAt: Option[Instant]
  ):
    def cells: Vector[String] = Vector(affiliateId, displayName, email, paymentMethod, brand, currency, period,
      cpa.toString, revShare.toString, overrideAmount.toString, adjustments.toString, total.toString, approvedBy,
      approvedAt.fold("")(_.toString))

  /** RFC 4180: CRLF line ends; a field with a comma, a quote, a line end or a leading space is quoted, quotes doubled. */
  def render(rows: Seq[Row]): String =
    (Header +: rows.map(_.cells)).map(_.map(field).mkString(",")).mkString("", "\r\n", "\r\n")

  /**
   * One field. A value starting `=`, `+`, `-`, `@` or a tab would be read as a formula by a spreadsheet;
   * an affiliate chose their display name, so such a value is prefixed with `'` (OWASP's advice). A
   * negative amount is our own number and keeps its sign.
   */
  def field(value: String): String =
    val guarded = if value.nonEmpty && "=+@\t".contains(value.head) then s"'$value" else if value.startsWith("-") && !value.drop(1).forall(_.isDigit) then s"'$value" else value
    if guarded.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') || guarded.startsWith(" ") then
      "\"" + guarded.replace("\"", "\"\"") + "\""
    else guarded
