package com.earthspay.transaction.smart.script

import com.earthspay.common.state.ByteStr
import com.earthspay.common.utils.Base64
import com.earthspay.lang.StdLibVersion._
import com.earthspay.lang.v1.compiler.Decompiler
import com.earthspay.transaction.ValidationError.ScriptParseError
import com.earthspay.transaction.smart.script.v1.ExprScript.ExprScriprImpl
import monix.eval.Coeval

trait Script {
  type Expr

  val stdLibVersion: StdLibVersion

  val expr: Expr

  val bytes: Coeval[ByteStr]
  val complexity: Long

  val containsBlockV2: Coeval[Boolean]

  override def equals(obj: scala.Any): Boolean = obj match {
    case that: Script => stdLibVersion == that.stdLibVersion && expr == that.expr
    case _            => false
  }

  override def hashCode(): Int = stdLibVersion * 31 + expr.hashCode()
}

object Script {

  val checksumLength = 4

  def fromBase64String(str: String): Either[ScriptParseError, Script] =
    for {
      bytes  <- Base64.decode(str).toEither.left.map(ex => ScriptParseError(s"Unable to decode base64: ${ex.getMessage}"))
      script <- ScriptReader.fromBytes(bytes)
    } yield script

  def decompile(s: Script): String = s match {
    case ExprScriprImpl(_, expr, _) => Decompiler(expr, com.earthspay.utils.defaultDecompilerContext)
    case com.earthspay.transaction.smart.script.ContractScript.ContractScriptImpl(_, contract, _) =>
      Decompiler(contract, com.earthspay.utils.defaultDecompilerContext)
  }
}
