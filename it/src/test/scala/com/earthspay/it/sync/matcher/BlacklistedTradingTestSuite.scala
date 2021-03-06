package com.earthspay.it.sync.matcher

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory.parseString
import com.earthspay.it.api.SyncHttpApi._
import com.earthspay.it.api.SyncMatcherHttpApi._
import com.earthspay.it.matcher.MatcherSuiteBase
import com.earthspay.it.sync._
import com.earthspay.it.sync.matcher.config.MatcherPriceAssetConfig._
import com.earthspay.transaction.assets.exchange.OrderType._
import org.scalatest._

class BlacklistedTradingTestSuite extends MatcherSuiteBase with GivenWhenThen {

  import BlacklistedTradingTestSuite._
  override protected def nodeConfigs: Seq[Config] = Configs.map(configWithBlacklisted().withFallback(_))

  private def matcher = dockerNodes().head
  private def alice   = dockerNodes()(1)
  private def bob     = dockerNodes()(2)

  Seq(IssueUsdTx, IssueWctTx, IssueEthTx, IssueBtcTx).map(createSignedIssueRequest).map(matcher.signedIssue).foreach { tx =>
    matcher.waitForTransaction(tx.id)
  }

  val (dec2, dec8) = (1000L, 1000000000L)

  "When blacklists are empty and some orders was placed" - {
    val usdOrder = matcher.placeOrder(alice.privateKey, earthsUsdPair, BUY, dec8, dec2, matcherFee).message.id
    val wctOrder = matcher.placeOrder(alice.privateKey, wctEarthsPair, BUY, dec2, dec8, matcherFee).message.id
    val ethOrder = matcher.placeOrder(alice.privateKey, ethEarthsPair, SELL, dec8, dec8, matcherFee).message.id
    val btcOrder = matcher.placeOrder(bob.privateKey, earthsBtcPair, SELL, dec8, dec8, matcherFee).message.id

    matcher.waitOrderStatus(wctEarthsPair, btcOrder, "Accepted")

    "If some assets and addresses are blacklisted" in {
      docker.restartNode(
        matcher,
        configWithBlacklisted(
          assets = Array(WctId.toString),
          names = Array("ETH.*"),
          addresses = Array(bob.address)
        )
      )

      Then("orders for blacklisted assets are not available and new orders can't be placed")
      matcher.orderStatusExpectInvalidAssetId(wctOrder, wctEarthsPair, WctId.toString)
      matcher.orderStatusExpectInvalidAssetId(ethOrder, ethEarthsPair, EthId.toString)
      matcher.expectRejectedOrderPlacement(alice.privateKey, wctEarthsPair, BUY, dec2, dec8)
      matcher.expectRejectedOrderPlacement(alice.privateKey, ethEarthsPair, SELL, dec8, dec8)
      matcher.expectRejectedOrderPlacement(bob.privateKey, earthsBtcPair, SELL, dec8, dec8)

      And("orders of blacklisted address are still available")
      matcher.orderStatus(btcOrder, earthsBtcPair).status shouldBe "Accepted"

      And("orders for other assets are still available")
      matcher.orderStatus(usdOrder, earthsUsdPair).status shouldBe "Accepted"

      And("OrderBook for blacklisted assets is not available")
      matcher.orderBookExpectInvalidAssetId(wctEarthsPair, WctId.toString)
      matcher.orderBookExpectInvalidAssetId(ethEarthsPair, EthId.toString)
      matcher.orderBook(earthsBtcPair).asks.size shouldBe 1

      And("OrderHistory returns info about all orders")
      matcher.activeOrderHistory(alice.privateKey).size shouldBe 3
      matcher.activeOrderHistory(alice.privateKey).foreach(_.status shouldBe "Accepted")
      matcher.activeOrderHistory(bob.privateKey).size shouldBe 1
      matcher.activeOrderHistory(bob.privateKey).head.status shouldBe "Accepted"

      And("Trading markets have info about all asset pairs")
      matcher.tradingMarkets().markets.size shouldBe 4

      And("balances are still reserved")
      matcher.reservedBalance(alice.privateKey).size shouldBe 3
      matcher.reservedBalance(bob.privateKey).size shouldBe 1

      And("orders for other assets are still available")
      matcher.orderStatus(usdOrder, earthsUsdPair).status shouldBe "Accepted"
    }

    "And now if all blacklists are cleared" in {
      docker.restartNode(matcher, configWithBlacklisted())

      Then("OrderBook for blacklisted assets is available again")
      matcher.orderBook(wctEarthsPair).bids.size shouldBe 1
      matcher.orderBook(ethEarthsPair).asks.size shouldBe 1

      And("order statuses are available again")
      matcher.orderStatus(wctOrder, wctEarthsPair).status shouldBe "Accepted"
      matcher.orderStatus(ethOrder, ethEarthsPair).status shouldBe "Accepted"

      And("new orders can be placed")
      val newWctOrder = matcher.placeOrder(alice.privateKey, wctEarthsPair, BUY, dec2, dec8, matcherFee).message.id
      val newEthOrder = matcher.placeOrder(alice.privateKey, ethEarthsPair, SELL, dec8, dec8, matcherFee).message.id
      val newBtcOrder = matcher.placeOrder(bob.privateKey, earthsBtcPair, SELL, dec8, dec8, matcherFee).message.id
      matcher.waitOrderStatus(wctEarthsPair, newBtcOrder, "Accepted")
      matcher.orderStatus(newWctOrder, wctEarthsPair).status shouldBe "Accepted"
      matcher.orderStatus(newEthOrder, ethEarthsPair).status shouldBe "Accepted"
    }

  }

}

object BlacklistedTradingTestSuite {

  def configWithBlacklisted(assets: Array[String] = Array(), names: Array[String] = Array(), addresses: Array[String] = Array()): Config = {
    def toStr(array: Array[String]): String = if (array.length == 0) "" else array.mkString("\"", "\", \"", "\"")
    parseString(s"""
                |earths.matcher {
                |  blacklisted-assets = [${toStr(assets)}]
                |  blacklisted-names = [${toStr(names)}]
                |  blacklisted-addresses = [${toStr(addresses)}]
                |}
    """.stripMargin)
  }

}
