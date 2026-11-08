package io.eitheror.casino.rewards.offers

import java.time.Instant
import java.util.Locale

/** An entry of the programme's code document for the brand (research R13): the offer a code names, and its tag. */
final case class ProgrammeEntry(offerId: String, tag: Option[String] = None)

/** What a code entered matched (FR-019): a brand offer's code, or an affiliate's from the programme's document. */
enum CodeMatch:
  case Brand(offerId: String)
  case Affiliate(offerId: String, tag: Option[String])

/**
 * Codes (research R4, settled 2026-11-01): stored as entered, matched and kept unique trimmed and in lower
 * case, across the brand's offers and the programme's document.
 */
object Codes:
  def normalise(code: String): String = code.trim.toLowerCase(Locale.ROOT)

  /** A code entered: a live offer's code first, then the programme's document (keyed normalised). */
  def find(code: String, offers: Iterable[Offer], programme: Map[String, ProgrammeEntry], now: Instant): Option[CodeMatch] =
    val n = normalise(code)
    offers.find(o => o.live(now) && o.redemptionCodes.exists(c => normalise(c.code) == n)).map(o => CodeMatch.Brand(o.id))
      .orElse(programme.get(n).map(e => CodeMatch.Affiliate(e.offerId, e.tag)))

  /** The codes other active offers hold, normalised, to the offer holding each (the original's uniqueness set). */
  def taken(offers: Iterable[Offer], excluding: String): Map[String, String] =
    offers.iterator.filter(o => o.active && o.id != excluding)
      .flatMap(o => o.redemptionCodes.map(c => normalise(c.code) -> o.id)).toMap

  /** Whether a list of codes names one code twice, in any case or spacing. */
  def repeats(codes: Iterable[RedemptionCode]): Boolean =
    val ns = codes.map(c => normalise(c.code)).toVector
    ns.distinct.size != ns.size

  /** The first of `codes`, as entered, that is already taken or in the programme's document. */
  def firstInUse(codes: Iterable[RedemptionCode], taken: Map[String, String], programme: Set[String]): Option[String] =
    val inDocument = programme.map(normalise)
    codes.find { c =>
      val n = normalise(c.code)
      taken.contains(n) || inDocument.contains(n)
    }.map(_.code)
