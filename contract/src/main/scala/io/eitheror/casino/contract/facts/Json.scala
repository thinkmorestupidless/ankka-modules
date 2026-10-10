package io.eitheror.casino.contract.facts

/** The little JSON the schema writer needs, rendered with sorted keys so schemas diff cleanly. */
enum Json:
  case Str(value: String)
  case Num(value: Long)
  case Bool(value: Boolean)
  case Arr(items: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

  def render(indent: Int = 0): String =
    val pad  = "  " * (indent + 1)
    val end  = "  " * indent
    this match
      case Str(v)  => Json.quote(v)
      case Num(v)  => v.toString
      case Bool(v) => v.toString
      case Arr(items) if items.isEmpty => "[]"
      case Arr(items) if items.forall { case Str(_) => true; case _ => false } =>
        items.map(_.render()).mkString("[", ", ", "]")
      case Arr(items) => items.map(i => pad + i.render(indent + 1)).mkString("[\n", ",\n", s"\n$end]")
      case Obj(fields) if fields.isEmpty => "{}"
      case Obj(fields) =>
        fields.sortBy(_._1).map((k, v) => s"$pad${Json.quote(k)}: ${v.render(indent + 1)}").mkString("{\n", ",\n", s"\n$end}")

object Json:
  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)
  def str(s: String): Json              = Str(s)

  def quote(s: String): String =
    val b = StringBuilder("\"")
    s.foreach {
      case '"'  => b ++= "\\\""
      case '\\' => b ++= "\\\\"
      case '\n' => b ++= "\\n"
      case c    => b += c
    }
    b += '"'
    b.result()
