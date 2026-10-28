package io.eitheror.casino.players.inbox

import scala.util.matching.Regex

/**
 * The merge tags an operator's message may name (FR-030), ported from the original's
 * `MergeTagRegistry`: the same names, labels and examples, the same `{{name}}` / `{{name|fallback}}`
 * syntax, the same single pass (a value is spliced in as text and never scanned again), the same refusal
 * of an unknown name for the whole render, and the same collapsing of the space beside an empty
 * substitution. The values are given by name: the route reads them from the player (`first_name` from
 * the profile, `vip_status` from the tier) and the wallet; a `Personal` value is revealed only into the
 * message body it renders, which is itself `Personal`.
 */
object MergeTags:

  final case class Tag(name: String, label: String, example: String, resolveAtOpen: Boolean = false)

  enum Refused:
    case UnknownTag(name: String)

  val tags: Vector[Tag] = Vector(
    Tag("first_name", "First name", "David"),
    Tag("verification_status", "Verification status", "Verified"),
    Tag("registration_date", "Registration date", "6 August 2025"),
    Tag("vip_status", "VIP status", "VIP Titan"),
    Tag("loyalty_status", "Loyalty status", "Expert"),
    Tag("my_account_profile_link", "My Account – Profile link", "internal://my-account/profile"),
    Tag("bonuses_section_link", "Bonuses section link", "internal://my-account/bonuses"),
    Tag("loyalty_program_link", "Loyalty program link", "internal://loyalty-program"),
    Tag("vip_program_link", "VIP program link", "internal://vip-program"),
    Tag("play_now_cta", "Play now CTA", "Play now"),
    Tag("cash_balance", "Cash balance", "€42.50", resolveAtOpen = true),
    Tag("bonus_balance", "Bonus balance", "€10.00", resolveAtOpen = true),
    Tag("wager_requirement", "Wager requirement", "x10", resolveAtOpen = true),
    Tag("remaining_wager", "Remaining wager", "€65.00", resolveAtOpen = true),
    Tag("free_spins_game", "Free spins game", "Book of Dead", resolveAtOpen = true)
  )

  private val byName: Map[String, Tag] = tags.map(t => t.name -> t).toMap

  /** The brand-constant values: the deep links, whatever the player (the original's static resolvers). */
  val constants: Map[String, String] = Map(
    "my_account_profile_link" -> "internal://my-account/profile",
    "bonuses_section_link"    -> "internal://my-account/bonuses",
    "loyalty_program_link"    -> "internal://loyalty-program",
    "vip_program_link"        -> "internal://vip-program"
  )

  /** The call to action in the player's language (the original's `play_now_cta`). */
  def playNow(language: Option[String]): String =
    language.map(_.take(2).toLowerCase) match
      case Some("fr") => "Jouer maintenant"
      case Some("de") => "Jetzt spielen"
      case Some("es") => "Jugar ahora"
      case Some("pt") => "Jogar agora"
      case _          => "Play now"

  /** What a preview shows for the tags only the inbox resolves: their examples. */
  val demo: Map[String, String] = tags.filter(_.resolveAtOpen).map(t => t.name -> t.example).toMap

  private val TagPattern: Regex = """\{\{\s*([a-z_][a-z0-9_]*)\s*(?:\|([^}]*))?\}\}""".r

  /** The tags `template` names that no value resolves (the preview's `hasAllValues`). */
  def unresolved(template: String, values: Map[String, String]): Either[Refused, Set[String]] =
    val names = TagPattern.findAllMatchIn(template).map(_.group(1)).toVector
    names.find(n => !byName.contains(n)) match
      case Some(n) => Left(Refused.UnknownTag(n))
      case None    => Right(names.filter(n => value(values, n).isEmpty).toSet)

  /** `template` with every tag replaced by its value, its fallback or nothing; refused naming the first unknown tag. */
  def render(template: String, values: Map[String, String]): Either[Refused, String] =
    val matches = TagPattern.findAllMatchIn(template).toList
    matches.map(_.group(1)).find(n => !byName.contains(n)) match
      case Some(n) => Left(Refused.UnknownTag(n))
      case None    => Right(assemble(segments(template, matches, values)))

  /** Every tag on its fallback (the preview's `fallback` variant); the brand constants still resolve. */
  def renderFallback(template: String): Either[Refused, String] = render(template, Map.empty)

  private def value(values: Map[String, String], name: String): Option[String] =
    values.get(name).orElse(constants.get(name)).orElse(Option.when(name == "play_now_cta")(playNow(None)))
      .map(_.trim).filter(_.nonEmpty)

  private final case class Segment(text: String, empty: Boolean)

  private def segments(template: String, matches: List[Regex.Match], values: Map[String, String]): List[Segment] =
    val out  = List.newBuilder[Segment]
    var last = 0
    matches.foreach { m =>
      if m.start > last then out += Segment(template.substring(last, m.start), empty = false)
      val replacement = value(values, m.group(1)).orElse(Option(m.group(2)).map(_.trim)).getOrElse("")
      out += Segment(replacement, empty = replacement.isEmpty)
      last = m.end
    }
    if last < template.length then out += Segment(template.substring(last), empty = false)
    out.result()

  private val trailing    = """[ \t]+$""".r
  private val leading     = """^[ \t]+""".r
  private val punctuation = Set(',', '.', ';', ':', '!', '?', ')')

  /** The segments joined, the space either side of an empty substitution collapsed (the original's `assemble`). */
  private def assemble(segments: List[Segment]): String =
    val arr = segments.toArray
    val sb  = new StringBuilder
    var i   = 0
    while i < arr.length do
      if !arr(i).empty then
        sb.append(arr(i).text)
        i += 1
      else
        val trimmed = trailing.replaceAllIn(sb.toString, "")
        val leftWs  = trimmed.length < sb.length
        sb.setLength(0)
        sb.append(trimmed)
        if i + 1 < arr.length && !arr(i + 1).empty then
          val next        = arr(i + 1).text
          val nextTrimmed = leading.replaceAllIn(next, "")
          val rightWs     = nextTrimmed.length < next.length
          val glue =
            if nextTrimmed.headOption.exists(punctuation.contains) then ""
            else if leftWs || rightWs then " "
            else ""
          sb.append(glue).append(nextTrimmed)
          i += 2
        else i += 1
    sb.toString
