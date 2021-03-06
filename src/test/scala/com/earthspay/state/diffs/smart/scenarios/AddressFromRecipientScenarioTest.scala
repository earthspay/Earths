package com.earthspay.state.diffs.smart.scenarios

import com.earthspay.account.{AddressOrAlias, PrivateKeyAccount}
import com.earthspay.common.utils.EitherExt2
import com.earthspay.lagonaki.mocks.TestBlock
import com.earthspay.lang.v1.compiler.Terms.{CONST_BYTESTR, CaseObj}
import com.earthspay.state.diffs.smart.predef._
import com.earthspay.state.diffs._
import com.earthspay.transaction.transfer._
import com.earthspay.transaction.{CreateAliasTransaction, GenesisTransaction}
import com.earthspay.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}

class AddressFromRecipientScenarioTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink {

  val preconditionsAndAliasCreations: Gen[(Seq[GenesisTransaction], CreateAliasTransaction, TransferTransactionV1, TransferTransactionV1)] = for {
    master                   <- accountGen
    ts                       <- timestampGen
    other: PrivateKeyAccount <- accountGen
    genesis1: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
    genesis2: GenesisTransaction = GenesisTransaction.create(other, ENOUGH_AMT, ts).explicitGet()
    alias              <- aliasGen
    fee                <- smallFeeGen
    aliasTx            <- createAliasGen(other, alias, fee, ts)
    transferViaAddress <- transferGeneratorP(master, other, None, None)
    transferViaAlias   <- transferGeneratorP(master, AddressOrAlias.fromBytes(alias.bytes.arr, 0).explicitGet()._1, None, None)
  } yield (Seq(genesis1, genesis2), aliasTx, transferViaAddress, transferViaAlias)

  val script = """
    | match tx {
    |  case t : TransferTransaction =>  addressFromRecipient(t.recipient)
    |  case other => throw()
    |  }
    |  """.stripMargin

  property("Script can resolve AddressOrAlias") {
    forAll(preconditionsAndAliasCreations) {
      case (gen, aliasTx, transferViaAddress, transferViaAlias) =>
        assertDiffAndState(Seq(TestBlock.create(gen)), TestBlock.create(Seq(aliasTx))) {
          case (_, state) =>
            val addressBytes = runScript[CaseObj](script, transferViaAddress, state).explicitGet().fields("bytes").asInstanceOf[CONST_BYTESTR]
            addressBytes.bs.arr.sameElements(transferViaAddress.recipient.bytes.arr) shouldBe true
            val resolvedAddressBytes =
              runScript[CaseObj](script, transferViaAlias, state).explicitGet().fields("bytes").asInstanceOf[CONST_BYTESTR]

            resolvedAddressBytes.bs.arr.sameElements(transferViaAddress.recipient.bytes.arr) shouldBe true
        }
    }
  }

  property("Script can't resolve alias that doesn't exist") {
    forAll(preconditionsAndAliasCreations) {
      case (gen, _, _, transferViaAlias) =>
        assertDiffAndState(Seq(TestBlock.create(gen)), TestBlock.create(Seq())) {
          case (_, state) =>
            runScript(script, transferViaAlias, state) should produce("AliasDoesNotExist")
        }
    }
  }
}
