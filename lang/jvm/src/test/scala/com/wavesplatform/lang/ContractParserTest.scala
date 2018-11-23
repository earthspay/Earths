package com.wavesplatform.lang

import com.wavesplatform.lang.Common._
import com.wavesplatform.lang.v1.parser.Expressions.Pos.AnyPos
import com.wavesplatform.lang.v1.parser.Expressions._
import com.wavesplatform.lang.v1.parser.{Expressions, Parser}
import com.wavesplatform.lang.v1.testing.ScriptGenParser
import fastparse.core.Parsed.{Failure, Success}
import org.scalatest.exceptions.TestFailedException
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}

class ContractParserTest extends PropSpec with PropertyChecks with Matchers with ScriptGenParser with NoShrink {

  private def parse(x: String): CONTRACT = Parser.parseContract(x) match {
    case Success(r, _)            => r
    case e: Failure[Char, String] => catchParseError(x, e)
  }

  private def catchParseError(x: String, e: Failure[Char, String]): Nothing = {
    import e.{index => i}
    println(s"val code1 = new String(Array[Byte](${x.getBytes.mkString(",")}))")
    println(s"""val code2 = "${escapedCode(x)}"""")
    println(s"Can't parse (len=${x.length}): <START>\n$x\n<END>\nError: $e\nPosition ($i): '${x.slice(i, i + 1)}'\nTraced:\n${e.extra.traced.fullStack
      .mkString("\n")}")
    throw new TestFailedException("Test failed", 0)
  }

  private def escapedCode(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case x    => x.toChar.toString
    }.mkString

  private def cleanOffsets(l: LET): LET =
    l.copy(Pos(0, 0), name = cleanOffsets(l.name), value = cleanOffsets(l.value), types = l.types.map(cleanOffsets(_)))

  private def cleanOffsets[T](p: PART[T]): PART[T] = p match {
    case PART.VALID(_, x)   => PART.VALID(AnyPos, x)
    case PART.INVALID(_, x) => PART.INVALID(AnyPos, x)
  }

  private def cleanOffsets(expr: EXPR): EXPR = expr match {
    case x: CONST_LONG                       => x.copy(position = Pos(0, 0))
    case x: REF                              => x.copy(position = Pos(0, 0), key = cleanOffsets(x.key))
    case x: CONST_STRING                     => x.copy(position = Pos(0, 0), value = cleanOffsets(x.value))
    case x: CONST_BYTEVECTOR                 => x.copy(position = Pos(0, 0), value = cleanOffsets(x.value))
    case x: TRUE                             => x.copy(position = Pos(0, 0))
    case x: FALSE                            => x.copy(position = Pos(0, 0))
    case x: BINARY_OP                        => x.copy(position = Pos(0, 0), a = cleanOffsets(x.a), b = cleanOffsets(x.b))
    case x: IF                               => x.copy(position = Pos(0, 0), cond = cleanOffsets(x.cond), ifTrue = cleanOffsets(x.ifTrue), ifFalse = cleanOffsets(x.ifFalse))
    case x @ BLOCK(_, l: Expressions.LET, _) => x.copy(position = Pos(0, 0), let = cleanOffsets(l), body = cleanOffsets(x.body))
    case x: FUNCTION_CALL                    => x.copy(position = Pos(0, 0), name = cleanOffsets(x.name), args = x.args.map(cleanOffsets(_)))
    case _                                   => throw new NotImplementedError(s"toString for ${expr.getClass.getSimpleName}")
  }

  property("simple 1-annotated function") {
    val code =
      """
        |
        | @Ann(foo)
        | func bar(arg:Baz) = {
        |    3
        | }
        |
        |
        |""".stripMargin
    parse(code) shouldBe CONTRACT(
      AnyPos,
      List.empty,
      List(
        ANNOTATEDFUNC(
          AnyPos,
          List(Expressions.ANNOTATION(AnyPos, PART.VALID(AnyPos, "Ann"), List(PART.VALID(AnyPos, "foo")))),
          Expressions.FUNC(
            AnyPos,
            PART.VALID(AnyPos, "bar"),
            List((PART.VALID(AnyPos, "arg"), List(PART.VALID(AnyPos, "Baz")))),
            CONST_LONG(AnyPos, 3)
          )
        )
      )
    )
  }

}