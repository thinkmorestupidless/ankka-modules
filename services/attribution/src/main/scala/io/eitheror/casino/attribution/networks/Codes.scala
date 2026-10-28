package io.eitheror.casino.attribution.networks

import org.slf4j.LoggerFactory

import java.time.Instant

/**
 * A bonus code told from the brand's own (research R6, R7): looked up, lower-cased, in every live
 * network's fresh code document, the first network added winning when two list it. A code is an
 * affiliate's only through the tag its document gives it; the tag is then verified as any other.
 */
object Codes:
  private val log = LoggerFactory.getLogger(getClass)

  sealed trait Outcome
  /** The network whose document lists the code, and the tag it names. */
  final case class Found(network: String, tag: String) extends Outcome
  /** Every fresh document was read and none lists the code: the brand's own code, or nobody's. */
  case object Unknown extends Outcome
  /** No network has a fresh code document, so whether it is an affiliate's cannot be told now. */
  case object Unresolved extends Outcome

  def resolve(code: String, networks: Vector[(NetworkState, DocumentsState)], now: Instant): Outcome =
    val wanted = code.trim.toLowerCase
    val fresh = networks.filter((n, d) => n.live && d.fresh(Documents.Kind.Codes, now))
      .sortBy((n, _) => (n.addedAt.getOrElse(Instant.EPOCH), n.id))
      .flatMap((n, d) => d.codes.map(n.id -> _))
    if fresh.isEmpty then Unresolved
    else
      val listing = fresh.flatMap((id, doc) => doc.codes.collectFirst { case (c, (_, tag)) if c.trim.toLowerCase == wanted => id -> tag })
      if listing.size > 1 then
        log.warn("bonus code listed by {} networks ({}); the first added, {}, wins", listing.size, listing.map(_._1).mkString(", "),
          listing.head._1)
      listing.headOption.fold[Outcome](Unknown)((id, tag) => Found(id, tag))
