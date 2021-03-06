package com.earthspay.history

import com.earthspay.TransactionGen
import com.earthspay.common.utils.EitherExt2
import com.earthspay.features.BlockchainFeatures
import com.earthspay.settings.{BlockchainSettings, EarthsSettings}
import com.earthspay.state.diffs.{ENOUGH_AMT, produce}
import com.earthspay.transaction.GenesisTransaction
import com.earthspay.transaction.assets.{BurnTransactionV1, IssueTransactionV1, ReissueTransactionV1}
import com.earthspay.transaction.transfer.TransferTransactionV1
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}

class BlockchainUpdaterBurnTest extends PropSpec with PropertyChecks with DomainScenarioDrivenPropertyCheck with Matchers with TransactionGen {

  val Earths: Long = 100000000

  type Setup =
    (Long, GenesisTransaction, TransferTransactionV1, IssueTransactionV1, BurnTransactionV1, ReissueTransactionV1)

  val preconditions: Gen[Setup] = for {
    master                                                   <- accountGen
    ts                                                       <- timestampGen
    transferAssetEarthsFee                                    <- smallFeeGen
    alice                                                    <- accountGen
    (_, assetName, description, quantity, decimals, _, _, _) <- issueParamGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
    masterToAlice: TransferTransactionV1 = TransferTransactionV1
      .selfSigned(None, master, alice, 3 * Earths, ts + 1, None, transferAssetEarthsFee, Array.emptyByteArray)
      .explicitGet()
    issue: IssueTransactionV1     = IssueTransactionV1.selfSigned(alice, assetName, description, quantity, decimals, false, Earths, ts + 100).explicitGet()
    burn: BurnTransactionV1       = BurnTransactionV1.selfSigned(alice, issue.assetId(), quantity / 2, Earths, ts + 200).explicitGet()
    reissue: ReissueTransactionV1 = ReissueTransactionV1.selfSigned(alice, issue.assetId(), burn.quantity, true, Earths, ts + 300).explicitGet()
  } yield (ts, genesis, masterToAlice, issue, burn, reissue)

  val localBlockchainSettings: BlockchainSettings = DefaultBlockchainSettings.copy(
    functionalitySettings = DefaultBlockchainSettings.functionalitySettings
      .copy(
        featureCheckBlocksPeriod = 1,
        blocksForFeatureActivation = 1,
        preActivatedFeatures = Map(BlockchainFeatures.NG.id -> 0, BlockchainFeatures.DataTransaction.id -> 0)
      ))
  val localEarthsSettings: EarthsSettings = settings.copy(blockchainSettings = localBlockchainSettings)

  property("issue -> burn -> reissue in sequential blocks works correctly") {
    scenario(preconditions, localEarthsSettings) {
      case (domain, (ts, genesis, masterToAlice, issue, burn, reissue)) =>
        val block0 = customBuildBlockOfTxs(randomSig, Seq(genesis), defaultSigner, 1, ts)
        val block1 = customBuildBlockOfTxs(block0.uniqueId, Seq(masterToAlice), defaultSigner, 1, ts + 150)
        val block2 = customBuildBlockOfTxs(block1.uniqueId, Seq(issue), defaultSigner, 1, ts + 250)
        val block3 = customBuildBlockOfTxs(block2.uniqueId, Seq(burn), defaultSigner, 1, ts + 350)
        val block4 = customBuildBlockOfTxs(block3.uniqueId, Seq(reissue), defaultSigner, 1, ts + 450)

        domain.appendBlock(block0)
        domain.appendBlock(block1)

        domain.appendBlock(block2)
        val assetDescription1 = domain.blockchainUpdater.assetDescription(issue.assetId()).get
        assetDescription1.reissuable should be(false)
        assetDescription1.totalVolume should be(issue.quantity)

        domain.appendBlock(block3)
        val assetDescription2 = domain.blockchainUpdater.assetDescription(issue.assetId()).get
        assetDescription2.reissuable should be(false)
        assetDescription2.totalVolume should be(issue.quantity - burn.quantity)

        domain.blockchainUpdater.processBlock(block4) should produce("Asset is not reissuable")
    }
  }

  property("issue -> burn -> reissue in micro blocks works correctly") {
    scenario(preconditions, localEarthsSettings) {
      case (domain, (ts, genesis, masterToAlice, issue, burn, reissue)) =>
        val block0 = customBuildBlockOfTxs(randomSig, Seq(genesis), defaultSigner, 1, ts)
        val block1 = customBuildBlockOfTxs(block0.uniqueId, Seq(masterToAlice), defaultSigner, 1, ts + 150)
        val block2 = customBuildBlockOfTxs(block1.uniqueId, Seq(issue), defaultSigner, 1, ts + 250)
        val block3 = customBuildBlockOfTxs(block2.uniqueId, Seq(burn, reissue), defaultSigner, 1, ts + 350)

        domain.appendBlock(block0)
        domain.appendBlock(block1)

        domain.appendBlock(block2)
        val assetDescription1 = domain.blockchainUpdater.assetDescription(issue.assetId()).get
        assetDescription1.reissuable should be(false)
        assetDescription1.totalVolume should be(issue.quantity)

        domain.blockchainUpdater.processBlock(block3) should produce("Asset is not reissuable")
    }
  }
}
