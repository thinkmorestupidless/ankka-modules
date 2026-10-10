package io.eitheror.casino.tools.tlfixtures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, DecimalNode, JsonNodeFactory, ObjectNode}

/**
 * Reads the original's TL handler fixtures (`starter-packs/wallet/handlers/<handler>.test.tl`) into JSON.
 *
 * The grammar, as the files use it:
 * {{{
 * file  := test*
 * test  := 'test' STRING '{' ('given' 'twin' object)* 'when' IDENT object then '}'
 * then  := 'then' '{' stmt* '}' | 'then' 'error' '(' STRING ')'
 * stmt  := 'mutation' INT ':' IDENT '(' value (',' value)* ')'
 *        | 'result' ':' value | 'error' ':' value | 'count' 'mutation' ':' INT
 * value := STRING | NUMBER | 'true' | 'false' | 'null' | '_' | object | list
 * object:= '{' (key ':' value ','?)* '}'      list := '[' (value ','?)* ']'
 * }}}
 * `#` starts a comment to the end of the line. Numbers keep their exact text (`70.00` stays
 * `70.00`); `_` (the original's "any value") becomes `{"$any": true}`. Anything else fails, naming
 * the file and line, because a fixture read wrongly would hold the rebuild to the wrong answer.
 */
object Parser:

  final case class ParseError(file: String, line: Int, message: String)
      extends RuntimeException(s"$file:$line: $message")

  private val factory = JsonNodeFactory.instance

  private enum Kind:
    case Str, Num, Ident, Punct

  private final case class Token(kind: Kind, text: String, line: Int)

  private def tokenize(file: String, src: String): Vector[Token] =
    val out  = Vector.newBuilder[Token]
    var i    = 0
    var line = 1
    def fail(msg: String) = throw ParseError(file, line, msg)
    while i < src.length do
      val c = src.charAt(i)
      if c == '\n' then { line += 1; i += 1 }
      else if c.isWhitespace then i += 1
      else if c == '#' then
        while i < src.length && src.charAt(i) != '\n' do i += 1
      else if c == '"' then
        val sb    = StringBuilder()
        val start = line
        i += 1
        while i < src.length && src.charAt(i) != '"' do
          val ch = src.charAt(i)
          if ch == '\n' then fail("unterminated string")
          if ch == '\\' && i + 1 < src.length then
            src.charAt(i + 1) match
              case 'n' => sb += '\n'
              case 't' => sb += '\t'
              case other => sb += other
            i += 2
          else
            sb += ch
            i += 1
        if i >= src.length then throw ParseError(file, start, "unterminated string")
        i += 1
        out += Token(Kind.Str, sb.result(), start)
      else if c.isDigit || (c == '-' && i + 1 < src.length && src.charAt(i + 1).isDigit) then
        val start = i
        i += 1
        while i < src.length && (src.charAt(i).isDigit || src.charAt(i) == '.') do i += 1
        out += Token(Kind.Num, src.substring(start, i), line)
      else if c.isLetter || c == '_' then
        val start = i
        while i < src.length && (src.charAt(i).isLetterOrDigit || src.charAt(i) == '_' || src.charAt(i) == '-' || src.charAt(i) == '.') do i += 1
        out += Token(Kind.Ident, src.substring(start, i), line)
      else if "{}[]():,".indexOf(c) >= 0 then
        out += Token(Kind.Punct, c.toString, line)
        i += 1
      else fail(s"unexpected character '$c'")
    out.result()

  final case class Case(name: String, line: Int, json: ObjectNode)

  /** Every `test` of one file, in order. */
  def parse(file: String, src: String): Vector[Case] =
    val toks = tokenize(file, src)
    var pos  = 0

    def lineHere: Int = if pos < toks.length then toks(pos).line else toks.lastOption.fold(1)(_.line)
    def fail(msg: String): Nothing = throw ParseError(file, lineHere, msg)
    def peek: Option[Token] = toks.lift(pos)
    def next(): Token = { val t = peek.getOrElse(fail("unexpected end of file")); pos += 1; t }
    def isPunct(p: String): Boolean = peek.exists(t => t.kind == Kind.Punct && t.text == p)
    def isIdent(w: String): Boolean = peek.exists(t => t.kind == Kind.Ident && t.text == w)
    def expectPunct(p: String): Unit =
      val t = next(); if t.kind != Kind.Punct || t.text != p then { pos -= 1; fail(s"expected '$p', found '${t.text}'") }
    def expectIdent(w: String): Unit =
      val t = next(); if t.kind != Kind.Ident || t.text != w then { pos -= 1; fail(s"expected '$w', found '${t.text}'") }
    def ident(): String =
      val t = next(); if t.kind != Kind.Ident then { pos -= 1; fail(s"expected a name, found '${t.text}'") }; t.text
    def string(): String =
      val t = next(); if t.kind != Kind.Str then { pos -= 1; fail(s"expected a string, found '${t.text}'") }; t.text
    def int(): Int =
      val t = next(); t.text.toIntOption.filter(_ => t.kind == Kind.Num).getOrElse { pos -= 1; fail(s"expected an integer, found '${t.text}'") }

    def number(text: String): JsonNode =
      if text.contains('.') then DecimalNode.valueOf(java.math.BigDecimal(text))
      else factory.numberNode(java.lang.Long.parseLong(text))

    def value(): JsonNode =
      val t = peek.getOrElse(fail("expected a value"))
      t.kind match
        case Kind.Str => pos += 1; factory.textNode(t.text)
        case Kind.Num => pos += 1; number(t.text)
        case Kind.Ident =>
          pos += 1
          t.text match
            case "true"  => factory.booleanNode(true)
            case "false" => factory.booleanNode(false)
            case "null"  => factory.nullNode()
            case "_"     => factory.objectNode().put("$any", true)
            case other   => pos -= 1; fail(s"unexpected bare word '$other' where a value belongs")
        case Kind.Punct if t.text == "{" => obj()
        case Kind.Punct if t.text == "[" => list()
        case _                           => fail(s"unexpected '${t.text}' where a value belongs")

    def obj(): ObjectNode =
      expectPunct("{")
      val o = factory.objectNode()
      while !isPunct("}") do
        val k = peek match
          case Some(Token(Kind.Ident, text, _)) => pos += 1; text
          case Some(Token(Kind.Str, text, _))   => pos += 1; text
          case _                                => fail(s"expected a field name, found '${peek.fold("end of file")(_.text)}'")
        expectPunct(":")
        if o.has(k) then fail(s"field '$k' given twice")
        o.set[JsonNode](k, value())
        if isPunct(",") then pos += 1
      expectPunct("}")
      o

    def list(): ArrayNode =
      expectPunct("[")
      val a = factory.arrayNode()
      while !isPunct("]") do
        a.add(value())
        if isPunct(",") then pos += 1
      expectPunct("]")
      a

    def mutation(): ObjectNode =
      val index = int()
      expectPunct(":")
      val op = ident()
      expectPunct("(")
      val args = Vector.newBuilder[JsonNode]
      while !isPunct(")") do
        args += value()
        if isPunct(",") then pos += 1
      expectPunct(")")
      val all = args.result()
      val m   = factory.objectNode().put("index", index).put("op", op)
      all.headOption match
        case Some(t) if t.isTextual => m.put("target", t.asText())
        case _                      => fail(s"mutation $index: the first argument of $op is its target and must be a string")
      all.tail match
        case Vector(props: ObjectNode) if op != "add_edge" => m.set[JsonNode]("properties", props)
        case rest =>
          val a = factory.arrayNode()
          rest.foreach(a.add)
          m.set[JsonNode]("args", a)
      m

    def thenBlock(): ObjectNode =
      expectIdent("then")
      val t         = factory.objectNode()
      val mutations = factory.arrayNode()
      t.set[JsonNode]("mutations", mutations)
      t.putNull("result")
      t.putNull("error")
      if isIdent("error") then
        pos += 1
        expectPunct("(")
        t.set[JsonNode]("error", factory.objectNode().put("code", string()))
        expectPunct(")")
      else
        expectPunct("{")
        while !isPunct("}") do
          ident() match
            case "mutation" =>
              val m = mutation()
              // A fixture may assert only some mutations by index (create-withdrawal-request asserts 0-3
              // and 5 of 8), so indices must increase but need not be contiguous.
              val last = if mutations.isEmpty then -1 else mutations.get(mutations.size() - 1).get("index").asInt()
              if m.get("index").asInt() <= last then fail(s"mutation ${m.get("index").asInt()} out of order after mutation $last")
              mutations.add(m)
            case "result" =>
              expectPunct(":"); if !t.get("result").isNull then fail("result given twice"); t.set[JsonNode]("result", value())
            case "error" =>
              expectPunct(":"); if !t.get("error").isNull then fail("error given twice"); t.set[JsonNode]("error", value())
            case "count" =>
              expectIdent("mutation"); expectPunct(":"); t.put("mutation_count", int())
            case other =>
              pos -= 1; fail(s"unknown statement '$other' in a then block")
        expectPunct("}")
      t

    def test(): Case =
      val line = lineHere
      expectIdent("test")
      val name = string()
      expectPunct("{")
      val givens = factory.arrayNode()
      while isIdent("given") do
        pos += 1
        expectIdent("twin")
        givens.add(obj())
      expectIdent("when")
      val command = ident()
      val params  = obj()
      val t       = thenBlock()
      expectPunct("}")
      val c = factory.objectNode()
      c.put("name", name)
      c.put("line", line)
      c.set[JsonNode]("given", givens)
      c.set[JsonNode]("when", factory.objectNode().put("command", command).set[JsonNode]("params", params))
      c.set[JsonNode]("then", t)
      Case(name, line, c)

    val cases = Vector.newBuilder[Case]
    while peek.isDefined do cases += test()
    cases.result()
