package io.eitheror.casino.players.registration

import io.eitheror.casino.players.player.CheckOutcome
import io.eitheror.casino.players.registration.LookupTokens.*

/**
 * What a duplicate check found: its outcome, the strong pairs (each other player with the signals it
 * shares with the new one), and for a weak outcome the signals that block the no-deposit offers.
 */
final case class Judgement(outcome: String, pairs: Map[String, Set[String]] = Map.empty, weak: Set[String] = Set.empty):
  def players: Set[String] = pairs.keySet
  def signals: Set[String] = pairs.values.flatten.toSet

/**
 * The strong and weak rules (spec 011 FR-035, research R9; the original's `MultiAccountDetector.applyFlags`).
 * A pair is strong when it shares the identity or the device signal, or an email variant together with a
 * registration from the same address within 24 hours or three or more registrations from the new player's
 * address within 24 hours. Without a strong pair an email variant is weak. The address's rate alone is
 * nothing: shared offices and carrier NAT put many people behind one address.
 */
object DuplicateRules:

  /** Registrations from one address within 24 hours, the new one counted, that make a variant strong. */
  val Rate = 3

  /**
   * `found`: the other players under each signal's token; `nearby`: the other players registered from the
   * new player's address within the 24 hours before it.
   */
  def judge(found: Map[String, Set[String]], nearby: Set[String]): Judgement =
    def under(signal: String) = found.getOrElse(signal, Set.empty)
    val rate     = nearby.size + 1 >= Rate
    val variants = under(EmailVariant)
    val direct   = Vector(Identity, Device).flatMap(signal => under(signal).toVector.map(_ -> signal))
    val viaEmail = variants.toVector.filter(p => nearby(p) || rate).flatMap { p =>
      (EmailVariant +: (Vector(Address).filter(_ => nearby(p)) ++ Vector(AddressRate).filter(_ => rate))).map(p -> _)
    }
    val pairs = (direct ++ viaEmail).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    if pairs.nonEmpty then Judgement(CheckOutcome.Strong, pairs)
    else if variants.nonEmpty then Judgement(CheckOutcome.Weak, weak = Set(EmailVariant))
    else Judgement(CheckOutcome.Clear)
