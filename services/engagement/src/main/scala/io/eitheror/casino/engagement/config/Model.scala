package io.eitheror.casino.engagement.config

import java.time.Instant

/**
 * A brand's Customer.io configuration, its secrets apart (data-model.md `Configuration`, FR-004): the site, the
 * region (`us` or `eu`), the sender, and the transactional id per trigger, keyed by `Seed.triggers` (absent is
 * disabled). The three keys are in the secret store under `Secrets`' names, never here.
 */
final case class CrmConfig(siteId: String = "", region: String = "us", fromEmail: String = "",
    triggers: Map[String, String] = Map.empty, updatedAt: Option[Instant] = None):
  def configured: Boolean = siteId.nonEmpty

  /** The transactional id for `trigger`, when one is set. */
  def transactional(trigger: String): Option[String] = triggers.get(trigger).filter(_.nonEmpty)

  /** What a `put` changed, by field (`triggers.<key>` for a trigger), for the audit record: names, no values. */
  def changes(next: CrmConfig): Vector[String] =
    Vector("siteId" -> (siteId != next.siteId), "region" -> (region != next.region), "fromEmail" -> (fromEmail != next.fromEmail))
      .collect { case (k, true) => k } ++
      (triggers.keySet ++ next.triggers.keySet).toVector.sorted.filter(k => transactional(k) != next.transactional(k)).map(k => s"triggers.$k")

object CrmConfig:
  /** Anything but `eu` is `us`, as the original. */
  def region(text: String): String = if text.trim.equalsIgnoreCase("eu") then "eu" else "us"
