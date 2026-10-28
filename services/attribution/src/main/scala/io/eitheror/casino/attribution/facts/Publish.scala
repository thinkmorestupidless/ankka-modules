package io.eitheror.casino.attribution.facts

import com.thinkmorestupidless.ankka.core.{Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact}

/**
 * Publishing to `affiliates.attribution` (contracts/facts.md): each fact keyed by its key (the pseudonym,
 * or the link for a click), its `ce-id` the fact id, so a redelivered event is the same fact (FR-022).
 * Its `ce-subject` is the key too: ankka defaults the subject to the source entity's id, which for a
 * record or a day's tally is the player id, and a player id never leaves the brand (SC-006).
 */
object Publish:
  def of(f: AttributionFact): Metadata = Metadata.empty.set("ce-id", f.factId).set(Metadata.CeSubject, f.key)

  val outputSerializer: Option[Serializer[AttributionFact]] = Some(AffiliatesAttribution.serializer)
  val produceTo: Option[String]                             = Some(AffiliatesAttribution.name)
  val produces: Option[Publication] = Some(Publication(AffiliatesAttribution.name, Some(AffiliatesAttribution.contract), None))
