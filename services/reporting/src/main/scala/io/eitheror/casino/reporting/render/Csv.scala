package io.eitheror.casino.reporting.render

/** CSV as the original writes it (`ReportService.scala:183-186`): CRLF lines, a cell quoted only when it must be. */
object Csv:
  def cell(s: String): String =
    if s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") then "\"" + s.replace("\"", "\"\"") + "\""
    else s

  def row(cells: Seq[String]): String = cells.map(cell).mkString(",") + "\r\n"

  def apply(header: Seq[String], rows: Iterable[Seq[String]]): String =
    val sb = StringBuilder(row(header))
    rows.foreach(r => sb.append(row(r)))
    sb.toString
