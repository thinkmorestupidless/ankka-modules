package io.eitheror.casino.contract.facts

/**
 * Whether a change to a topic keeps its version (spec 001 FR-013): a family may gain fields and a
 * topic may gain families; removing a field, changing its type or making an optional field required
 * changes the family's meaning, and is a new version beside the old (`<family>.v<n>`), the old still
 * produced until no brand reads it.
 */
object Compatibility:

  enum Verdict:
    case SameVersion
    case NewVersion(reasons: Vector[String])

  /** Compares families by name: `before` is what readers know, `after` what a producer would write. */
  def compare(before: Vector[FamilyDef[?]], after: Vector[FamilyDef[?]]): Verdict =
    val reasons = before.flatMap { old =>
      after.find(_.name == old.name) match
        case None => Vector(s"${old.name} is gone; a family is replaced by a new version beside it, never removed")
        case Some(now) =>
          val nowFields = now.fields.toMap
          old.fields.flatMap { (field, kind) =>
            nowFields.get(field) match
              case None => Some(s"${old.name}.$field is removed")
              case Some(k) if k.schema != kind.schema => Some(s"${old.name}.$field changes type")
              case Some(k) if kind.optional && !k.optional => Some(s"${old.name}.$field becomes required")
              case _ => None
          } ++ now.fields.collect {
            case (field, k) if !old.fields.exists(_._1 == field) && !k.optional =>
              s"${old.name}.$field is added as required; an added field is optional, so readers of the old shape still read it"
          }
    }
    if reasons.isEmpty then Verdict.SameVersion else Verdict.NewVersion(reasons)

  /** The name a changed family takes beside the old one. */
  def nextVersion(family: String): String =
    "\\.v([0-9]+)$".r.findFirstMatchIn(family) match
      case Some(m) => family.dropRight(m.matched.length) + s".v${m.group(1).toInt + 1}"
      case None    => s"$family.v2"
