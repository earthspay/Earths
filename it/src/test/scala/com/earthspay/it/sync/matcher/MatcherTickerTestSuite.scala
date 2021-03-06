package com.earthspay.it.sync.matcher

import com.typesafe.config.{Config, ConfigFactory}
import com.earthspay.account.PrivateKeyAccount
import com.earthspay.it.ReportingTestName
import com.earthspay.it.api.SyncHttpApi._
import com.earthspay.it.api.SyncMatcherHttpApi
import com.earthspay.it.api.SyncMatcherHttpApi._
import com.earthspay.it.sync.CustomFeeTransactionSuite.defaultAssetQuantity
import com.earthspay.it.sync._
import com.earthspay.it.transactions.NodesFromDocker
import com.earthspay.it.util._
import com.earthspay.transaction.AssetId
import com.earthspay.transaction.assets.IssueTransactionV1
import com.earthspay.transaction.assets.exchange.{AssetPair, OrderType}
import org.scalatest._

import scala.util.Random

class MatcherTickerTestSuite
    extends FreeSpec
    with Matchers
    with BeforeAndAfterAll
    with CancelAfterFailure
    with NodesFromDocker
    with ReportingTestName {

  import MatcherTickerTestSuite._

  override protected def nodeConfigs: Seq[Config] = Configs

  private def matcherNode = nodes.head

  private def aliceNode = nodes(1)

  private def bobNode = nodes(2)

  val issueTx = matcherNode.signedIssue(createSignedIssueRequest(IssueUsdTx))
  matcherNode.waitForTransaction(issueTx.id)

  "matcher ticker validation" - {
    "get tickers for unavailable asset should produce error" in {
      SyncMatcherHttpApi.assertNotFoundAndMessage(matcherNode.marketStatus(wctEarthsPair), s"Invalid Asset ID: ${IssueEightDigitAssetTx.id()}")
    }

    "status of empty orderbook" in {
//    TODO: add error message after fix of https://earthspay.atlassian.net/browse/NODE-1151
//      SyncMatcherHttpApi.assertNotFoundAndMessage(matcherNode.marketStatus(earthsUsdPair), s"")
    }

    "error of non-existed order" in {
      //TODO: add error message after fix of https://earthspay.atlassian.net/browse/NODE-1151
//      SyncMatcherHttpApi.assertNotFoundAndMessage(matcherNode.orderStatus(IssueUsdTx.id().toString, earthsUsdPair), s"")
    }

    "try to work with incorrect pair" in {
      val usdEarthsPair = AssetPair(
        amountAsset = Some(UsdId),
        priceAsset = None
      )

      assert(
        matcherNode
          .matcherGet(s"/matcher/orderbook/${usdEarthsPair.amountAssetStr}/${usdEarthsPair.priceAssetStr}/status", statusCode = 301)
          .getHeader("Location")
          .contains(s"EARTHS/${usdEarthsPair.amountAssetStr}"))

      //TODO: add error message after fix of https://earthspay.atlassian.net/browse/NODE-1151
//      SyncMatcherHttpApi.assertNotFoundAndMessage(matcherNode.placeOrder(aliceNode, usdEarthsPair, OrderType.BUY, 1.earths, 200), "")
    }

    "issue tokens" in {
      val tx = matcherNode.signedIssue(createSignedIssueRequest(IssueEightDigitAssetTx))
      matcherNode.waitForTransaction(tx.id)
    }

    val bidPrice  = 200
    val bidAmount = 1.earths
    val askPrice  = 400
    val askAmount = bidAmount / 2

    "place bid order for first pair" in {
      matcherNode.placeOrder(aliceNode.privateKey, edUsdPair, OrderType.BUY, bidAmount, bidPrice, matcherFee)
      val aliceOrder = matcherNode.placeOrder(aliceNode.privateKey, edUsdPair, OrderType.BUY, bidAmount, bidPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(edUsdPair, aliceOrder, "Accepted")

      val r = matcherNode.marketStatus(edUsdPair)
      r.lastPrice shouldBe None
      r.lastSide shouldBe None
      r.bid shouldBe Some(bidPrice)
      r.bidAmount shouldBe Some(2 * bidAmount)
      r.ask shouldBe None
      r.askAmount shouldBe None
    }

    "place ask order for second pair" in {
      matcherNode.placeOrder(bobNode.privateKey, wctEarthsPair, OrderType.SELL, askAmount, askPrice, matcherFee)
      val bobOrder = matcherNode.placeOrder(bobNode.privateKey, wctEarthsPair, OrderType.SELL, askAmount, askPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(wctEarthsPair, bobOrder, "Accepted")
      val r = matcherNode.marketStatus(wctEarthsPair)
      r.lastPrice shouldBe None
      r.lastSide shouldBe None
      r.bid shouldBe None
      r.bidAmount shouldBe None
      r.ask shouldBe Some(askPrice)
      r.askAmount shouldBe Some(2 * askAmount)
    }

    "place ask order for first pair" in {
      matcherNode.placeOrder(bobNode.privateKey, edUsdPair, OrderType.SELL, askAmount, askPrice, matcherFee)
      val bobOrder = matcherNode.placeOrder(bobNode.privateKey, edUsdPair, OrderType.SELL, askAmount, askPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(edUsdPair, bobOrder, "Accepted")
      val r = matcherNode.marketStatus(edUsdPair)
      r.lastPrice shouldBe None
      r.lastSide shouldBe None
      r.bid shouldBe Some(bidPrice)
      r.bidAmount shouldBe Some(2 * bidAmount)
      r.ask shouldBe Some(askPrice)
      r.askAmount shouldBe Some(2 * askAmount)
    }

    "match bid order for first pair" in {
      val bobOrder = matcherNode.placeOrder(bobNode.privateKey, edUsdPair, OrderType.SELL, askAmount, bidPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(edUsdPair, bobOrder, "Filled")
      val r = matcherNode.marketStatus(edUsdPair)
      r.lastPrice shouldBe Some(bidPrice)
      r.lastSide shouldBe Some("sell")
      r.bid shouldBe Some(bidPrice)
      r.bidAmount shouldBe Some(2 * bidAmount - askAmount)
      r.ask shouldBe Some(askPrice)
      r.askAmount shouldBe Some(2 * askAmount)

      val bobOrder1 = matcherNode.placeOrder(bobNode.privateKey, edUsdPair, OrderType.SELL, 3 * askAmount, bidPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(edUsdPair, bobOrder1, "Filled")
      val s = matcherNode.marketStatus(edUsdPair)
      s.lastPrice shouldBe Some(bidPrice)
      s.lastSide shouldBe Some("sell")
      s.bid shouldBe None
      s.bidAmount shouldBe None
      s.ask shouldBe Some(askPrice)
      s.askAmount shouldBe Some(2 * askAmount)
    }

    "match ask order for first pair" in {
      val aliceOrder = matcherNode.placeOrder(aliceNode.privateKey, edUsdPair, OrderType.BUY, bidAmount, askPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(edUsdPair, aliceOrder, "Filled")
      val r = matcherNode.marketStatus(edUsdPair)
      r.lastPrice shouldBe Some(askPrice)
      r.lastSide shouldBe Some("buy")
      r.bid shouldBe None
      r.bidAmount shouldBe None
      r.ask shouldBe None
      r.askAmount shouldBe None
    }

  }

}

object MatcherTickerTestSuite {

  import ConfigFactory._
  import com.earthspay.it.NodeConfigs._

  private val ForbiddenAssetId = "FdbnAsset"
  val Decimals: Byte           = 2

  private val minerDisabled = parseString("earths.miner.enable = no")
  private val matcherConfig = parseString(s"""
                                             |earths.matcher {
                                             |  enable = yes
                                             |  account = 3HmFkAoQRs4Y3PE2uR6ohN7wS4VqPBGKv7k
                                             |  bind-address = "0.0.0.0"
                                             |  order-match-tx-fee = 300000
                                             |  blacklisted-assets = ["$ForbiddenAssetId"]
                                             |  balance-watching.enable = yes
                                             |}""".stripMargin)

  private val _Configs: Seq[Config] = (Default.last +: Random.shuffle(Default.init).take(3))
    .zip(Seq(matcherConfig, minerDisabled, minerDisabled, empty()))
    .map { case (n, o) => o.withFallback(n) }

  private val aliceSeed = _Configs(1).getString("account-seed")
  private val bobSeed   = _Configs(2).getString("account-seed")
  private val alicePk   = PrivateKeyAccount.fromSeed(aliceSeed).right.get
  private val bobPk     = PrivateKeyAccount.fromSeed(bobSeed).right.get

  val usdAssetName             = "USD-X"
  val eightDigitAssetAssetName = "Eight-X"
  val IssueUsdTx: IssueTransactionV1 = IssueTransactionV1
    .selfSigned(
      sender = alicePk,
      name = usdAssetName.getBytes(),
      description = "asset description".getBytes(),
      quantity = defaultAssetQuantity,
      decimals = Decimals,
      reissuable = false,
      fee = 1.earths,
      timestamp = System.currentTimeMillis()
    )
    .right
    .get

  val IssueEightDigitAssetTx: IssueTransactionV1 = IssueTransactionV1
    .selfSigned(
      sender = bobPk,
      name = eightDigitAssetAssetName.getBytes(),
      description = "asset description".getBytes(),
      quantity = defaultAssetQuantity,
      decimals = 8,
      reissuable = false,
      fee = 1.earths,
      timestamp = System.currentTimeMillis()
    )
    .right
    .get

  val UsdId: AssetId    = IssueUsdTx.id()
  val EightDigitAssetId = IssueEightDigitAssetTx.id()

  val edUsdPair = AssetPair(
    amountAsset = Some(EightDigitAssetId),
    priceAsset = Some(UsdId)
  )

  val wctEarthsPair = AssetPair(
    amountAsset = Some(EightDigitAssetId),
    priceAsset = None
  )

  val earthsUsdPair = AssetPair(
    amountAsset = None,
    priceAsset = Some(UsdId)
  )

  private val updatedMatcherConfig = parseString(s"""
                                                    |earths.matcher {
                                                    |  price-assets = [ "$UsdId", "EARTHS"]
                                                    |}
     """.stripMargin)

  private val Configs = _Configs.map(updatedMatcherConfig.withFallback(_))
}
