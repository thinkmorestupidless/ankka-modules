package io.eitheror.casino.support.domain

import java.util.regex.Pattern

/**
 * The `softening` output guardrail's rule (contracts/tools.md, *Softening*; FR-022): a reply in a turn about a break or
 * an exclusion must not carry a phrase that makes the control sound reversible. Phrases match as whole words in any
 * case, a curly apostrophe as a straight one.
 */
object Softening:
  val Phrases: Vector[String] = Vector("you can always", "you can change", "you could reverse", "can be undone", "just temporary",
    "only temporary", "don't worry", "no big deal", "easily reverse", "come back any time", "shorten it", "lift it early", "reverse it")

  /** The tools whose turn is about a protection. */
  val Protective: Set[String] = Set("apply_cool_off", "apply_self_exclusion")

  private def words(p: String) = Pattern.compile(s"(?<![\\p{L}\\p{N}])${Pattern.quote(p)}(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE)

  private val phrases = Phrases.map(p => p -> words(p))
  private val about   = Pattern.compile("(?<![\\p{L}\\p{N}])(break|cool(ing)?[- ]off|(self-)?exclu(sion|ded|de))(?![\\p{L}\\p{N}])",
    Pattern.CASE_INSENSITIVE)

  /** The first phrase `reply` carries, when the turn offers a protective tool or the reply names a protection. */
  def refuses(tools: Set[String], reply: String): Option[String] =
    val text = reply.replace('’', '\'')
    if tools.intersect(Protective).isEmpty && !about.matcher(text).find() then None
    else phrases.collectFirst { case (p, pattern) if pattern.matcher(text).find() => p }
