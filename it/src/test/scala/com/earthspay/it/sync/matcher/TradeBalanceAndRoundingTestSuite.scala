package com.earthspay.it.sync.matcher

import com.typesafe.config.Config
import com.earthspay.it.api.AssetDecimalsInfo
import com.earthspay.it.api.SyncHttpApi._
import com.earthspay.it.api.SyncMatcherHttpApi._
import com.earthspay.it.matcher.MatcherSuiteBase
import com.earthspay.it.sync._
import com.earthspay.it.sync.matcher.config.MatcherPriceAssetConfig._
import com.earthspay.it.util._
import com.earthspay.matcher.model.LimitOrder
import com.earthspay.transaction.assets.exchange.OrderType.{BUY, SELL}
import com.earthspay.transaction.assets.exchange.{Order, OrderType}

import scala.concurrent.duration._
import scala.math.BigDecimal.RoundingMode

class TradeBalanceAndRoundingTestSuite extends MatcherSuiteBase {

  override protected def nodeConfigs: Seq[Config] = Configs

  Seq(IssueUsdTx, IssueEthTx, IssueWctTx).map(createSignedIssueRequest).map(matcherNode.signedIssue).foreach { tx =>
    matcherNode.waitForTransaction(tx.id)
  }

  Seq(
    aliceNode.transfer(aliceNode.address, aliceAcc.address, defaultAssetQuantity, 100000, Some(UsdId.toString), None, 2),
    aliceNode.transfer(aliceNode.address, aliceAcc.address, defaultAssetQuantity, 100000, Some(EthId.toString), None, 2),
    bobNode.transfer(bobNode.address, bobAcc.address, defaultAssetQuantity, 100000, Some(WctId.toString), None, 2)
  ).foreach { tx =>
    matcherNode.waitForTransaction(tx.id)
  }

  "Alice and Bob trade EARTHS-USD" - {
    val aliceEarthsBalanceBefore = matcherNode.accountBalances(aliceAcc.address)._1
    val bobEarthsBalanceBefore   = matcherNode.accountBalances(bobAcc.address)._1

    val price           = 238
    val buyOrderAmount  = 425532L
    val sellOrderAmount = 3100000000L

    val correctedSellAmount = correctAmount(sellOrderAmount, price)

    val adjustedAmount = receiveAmount(OrderType.BUY, buyOrderAmount, price)
    val adjustedTotal  = receiveAmount(OrderType.SELL, buyOrderAmount, price)

    log.debug(s"correctedSellAmount: $correctedSellAmount, adjustedAmount: $adjustedAmount, adjustedTotal: $adjustedTotal")

    "place usd-earths order" in {
      // Alice wants to sell USD for Earths

      val bobOrder1   = matcherNode.prepareOrder(bobAcc, earthsUsdPair, OrderType.SELL, sellOrderAmount, price)
      val bobOrder1Id = matcherNode.placeOrder(bobOrder1).message.id
      matcherNode.waitOrderStatus(earthsUsdPair, bobOrder1Id, "Accepted", 1.minute)
      matcherNode.reservedBalance(bobAcc)("EARTHS") shouldBe sellOrderAmount + matcherFee
      matcherNode.tradableBalance(bobAcc, earthsUsdPair)("EARTHS") shouldBe bobEarthsBalanceBefore - (sellOrderAmount + matcherFee)

      val aliceOrder   = matcherNode.prepareOrder(aliceAcc, earthsUsdPair, OrderType.BUY, buyOrderAmount, price)
      val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id
      matcherNode.waitOrderStatusAndAmount(earthsUsdPair, aliceOrderId, "Filled", Some(420169L), 1.minute)

      // Bob wants to buy some USD
      matcherNode.waitOrderStatusAndAmount(earthsUsdPair, bobOrder1Id, "PartiallyFilled", Some(420169L), 1.minute)

      // Each side get fair amount of assets
      val exchangeTx = matcherNode.transactionsByOrder(aliceOrder.idStr()).headOption.getOrElse(fail("Expected an exchange transaction"))
      matcherNode.waitForTransaction(exchangeTx.id)
    }

    "get opened trading markets. USD price-asset " in {
      val openMarkets = matcherNode.tradingMarkets()
      openMarkets.markets.size shouldBe 1
      val markets = openMarkets.markets.head

      markets.amountAssetName shouldBe "EARTHS"
      markets.amountAssetInfo shouldBe Some(AssetDecimalsInfo(8))

      markets.priceAssetName shouldBe usdAssetName
      markets.priceAssetInfo shouldBe Some(AssetDecimalsInfo(Decimals))
    }

    "check usd and earths balance after fill" in {
      val aliceEarthsBalanceAfter = matcherNode.accountBalances(aliceAcc.address)._1
      val aliceUsdBalance        = matcherNode.assetBalance(aliceAcc.address, UsdId.base58).balance

      val bobEarthsBalanceAfter = matcherNode.accountBalances(bobAcc.address)._1
      val bobUsdBalance        = matcherNode.assetBalance(bobAcc.address, UsdId.base58).balance

      (aliceEarthsBalanceAfter - aliceEarthsBalanceBefore) should be(
        adjustedAmount - (BigInt(matcherFee) * adjustedAmount / buyOrderAmount).bigInteger.longValue())

      aliceUsdBalance - defaultAssetQuantity should be(-adjustedTotal)
      bobEarthsBalanceAfter - bobEarthsBalanceBefore should be(
        -adjustedAmount - (BigInt(matcherFee) * adjustedAmount / sellOrderAmount).bigInteger.longValue())
      bobUsdBalance should be(adjustedTotal)
    }

    "check filled amount and tradable balance" in {
      val bobsOrderId  = matcherNode.fullOrderHistory(bobAcc).head.id
      val filledAmount = matcherNode.orderStatus(bobsOrderId, earthsUsdPair).filledAmount.getOrElse(0L)

      filledAmount shouldBe adjustedAmount
    }

    "check reserved balance" in {
      val reservedFee = BigInt(matcherFee) - (BigInt(matcherFee) * adjustedAmount / sellOrderAmount)
      log.debug(s"reservedFee: $reservedFee")
      val expectedBobReservedBalance = correctedSellAmount - adjustedAmount + reservedFee
      matcherNode.reservedBalance(bobAcc)("EARTHS") shouldBe expectedBobReservedBalance

      matcherNode.reservedBalance(aliceAcc) shouldBe empty
    }

    "check earths-usd tradable balance" in {
      val orderHistory = matcherNode.fullOrderHistory(bobAcc)
      orderHistory.size should be(1)

      val expectedBobTradableBalance = bobEarthsBalanceBefore - (correctedSellAmount + matcherFee)
      matcherNode.tradableBalance(bobAcc, earthsUsdPair)("EARTHS") shouldBe expectedBobTradableBalance
      matcherNode.tradableBalance(aliceAcc, earthsUsdPair)("EARTHS") shouldBe aliceNode.accountBalances(aliceAcc.address)._1

      val orderId = orderHistory.head.id
      matcherNode.cancelOrder(bobAcc, earthsUsdPair, orderId)
      matcherNode.waitOrderStatus(earthsUsdPair, orderId, "Cancelled", 1.minute)
      matcherNode.tradableBalance(bobAcc, earthsUsdPair)("EARTHS") shouldBe bobNode.accountBalances(bobAcc.address)._1
    }
  }

  "Alice and Bob trade EARTHS-USD check CELLING" - {
    val price2           = 289
    val buyOrderAmount2  = 0.07.earths
    val sellOrderAmount2 = 3.earths

    val correctedSellAmount2 = correctAmount(sellOrderAmount2, price2)

    "place usd-earths order" in {
      // Alice wants to sell USD for Earths
      val bobEarthsBalanceBefore = matcherNode.accountBalances(bobAcc.address)._1
      matcherNode.tradableBalance(bobAcc, earthsUsdPair)("EARTHS")
      val bobOrder1   = matcherNode.prepareOrder(bobAcc, earthsUsdPair, OrderType.SELL, sellOrderAmount2, price2)
      val bobOrder1Id = matcherNode.placeOrder(bobOrder1).message.id
      matcherNode.waitOrderStatus(earthsUsdPair, bobOrder1Id, "Accepted", 1.minute)

      matcherNode.reservedBalance(bobAcc)("EARTHS") shouldBe correctedSellAmount2 + matcherFee
      matcherNode.tradableBalance(bobAcc, earthsUsdPair)("EARTHS") shouldBe bobEarthsBalanceBefore - (correctedSellAmount2 + matcherFee)

      val aliceOrder   = matcherNode.prepareOrder(aliceAcc, earthsUsdPair, OrderType.BUY, buyOrderAmount2, price2)
      val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id
      matcherNode.waitOrderStatus(earthsUsdPair, aliceOrderId, "Filled", 1.minute)

      // Bob wants to buy some USD
      matcherNode.waitOrderStatus(earthsUsdPair, bobOrder1Id, "PartiallyFilled", 1.minute)

      // Each side get fair amount of assets
      val exchangeTx = matcherNode.transactionsByOrder(aliceOrder.idStr()).headOption.getOrElse(fail("Expected an exchange transaction"))
      matcherNode.waitForTransaction(exchangeTx.id)
      matcherNode.cancelOrder(bobAcc, earthsUsdPair, bobOrder1Id)
    }

  }

  "Alice and Bob trade WCT-USD sell price less than buy price" - {
    "place wcd-usd order corrected by new price sell amount less then initial one" in {
      val buyPrice   = 247700
      val sellPrice  = 135600
      val buyAmount  = 46978
      val sellAmount = 56978

      val bobOrderId = matcherNode.placeOrder(bobAcc, wctUsdPair, SELL, sellAmount, sellPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Accepted", 1.minute)
      val aliceOrderId = matcherNode.placeOrder(aliceAcc, wctUsdPair, BUY, buyAmount, buyPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(aliceOrderId).headOption.getOrElse(fail("Expected an exchange transaction"))
      matcherNode.waitForTransaction(exchangeTx.id)
      matcherNode.cancelOrder(bobAcc, wctUsdPair, bobOrderId)

      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Cancelled", 1.minute)

      matcherNode.reservedBalance(bobAcc) shouldBe empty
      matcherNode.reservedBalance(aliceAcc) shouldBe empty
    }
  }

  "Alice and Bob trade WCT-USD 1" - {
    val wctUsdSellAmount = 347
    val wctUsdBuyAmount  = 146
    val wctUsdPrice      = 12739213

    "place wct-usd order" in {
      nodes.waitForSameBlockHeadesAt(nodes.map(_.height).max + 1)

      val aliceUsdBalance   = matcherNode.assetBalance(aliceAcc.address, UsdId.base58).balance
      val bobUsdBalance     = matcherNode.assetBalance(bobAcc.address, UsdId.base58).balance
      val bobWctInitBalance = matcherNode.assetBalance(bobAcc.address, WctId.base58).balance

      val bobOrderId =
        matcherNode.placeOrder(bobAcc, wctUsdPair, SELL, wctUsdSellAmount, wctUsdPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Accepted", 1.minute)

      val aliceOrderId =
        matcherNode.placeOrder(aliceAcc, wctUsdPair, BUY, wctUsdBuyAmount, wctUsdPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(aliceOrderId).headOption.getOrElse(fail("Expected an exchange transaction"))
      matcherNode.waitForTransaction(exchangeTx.id)

      val executedAmount         = correctAmount(wctUsdBuyAmount, wctUsdPrice) // 142
      val bobReceiveUsdAmount    = receiveAmount(SELL, wctUsdBuyAmount, wctUsdPrice)
      val expectedReservedBobWct = wctUsdSellAmount - executedAmount // 205 = 347 - 142

      matcherNode.reservedBalance(bobAcc)(s"$WctId") shouldBe expectedReservedBobWct
      // 999999999652 = 999999999999 - 142 - 205
      matcherNode.tradableBalance(bobAcc, wctUsdPair)(s"$WctId") shouldBe bobWctInitBalance - executedAmount - expectedReservedBobWct
      matcherNode.tradableBalance(bobAcc, wctUsdPair)(s"$UsdId") shouldBe bobUsdBalance + bobReceiveUsdAmount

      matcherNode.reservedBalance(aliceAcc) shouldBe empty
      matcherNode.tradableBalance(aliceAcc, wctUsdPair)(s"$UsdId") shouldBe aliceUsdBalance - bobReceiveUsdAmount

      val expectedReservedEarths = matcherFee - LimitOrder.partialFee(matcherFee, wctUsdSellAmount, executedAmount)
      matcherNode.reservedBalance(bobAcc)("EARTHS") shouldBe expectedReservedEarths

      matcherNode.cancelOrder(bobAcc, wctUsdPair, matcherNode.fullOrderHistory(bobAcc).head.id)
    }

    "reserved balance is empty after the total execution" in {
      val aliceOrderId = matcherNode.placeOrder(aliceAcc, wctUsdPair, BUY, 5000000, 100000, matcherFee).message.id
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Accepted", 1.minute)

      val bobOrderId = matcherNode.placeOrder(bobAcc, wctUsdPair, SELL, 5000000, 99908, matcherFee).message.id
      matcherNode.waitOrderStatus(wctUsdPair, bobOrderId, "Filled", 1.minute)
      matcherNode.waitOrderStatus(wctUsdPair, aliceOrderId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(bobOrderId).headOption.getOrElse(fail("Expected an exchange transaction"))
      matcherNode.waitForTransaction(exchangeTx.id)

      matcherNode.reservedBalance(aliceAcc) shouldBe empty
      matcherNode.reservedBalance(bobAcc) shouldBe empty
    }

  }

  "get opened trading markets. Check WCT-USD" in {
    val openMarkets = matcherNode.tradingMarkets()
    val markets     = openMarkets.markets.last

    markets.amountAssetName shouldBe wctAssetName
    markets.amountAssetInfo shouldBe Some(AssetDecimalsInfo(Decimals))

    markets.priceAssetName shouldBe usdAssetName
    markets.priceAssetInfo shouldBe Some(AssetDecimalsInfo(Decimals))
  }

  "Alice and Bob trade WCT-EARTHS on not enough fee when place order" - {
    val wctEarthsSellAmount = 2
    val wctEarthsPrice      = 11234560000000L

    "bob lease all earths exact half matcher fee" in {
      val leasingAmount = bobNode.accountBalances(bobAcc.address)._1 - leasingFee - matcherFee / 2
      val leaseTxId     = bobNode.lease(bobAcc.address, matcherAcc.address, leasingAmount, leasingFee, 2).id
      matcherNode.waitForTransaction(leaseTxId)
      val bobOrderId =
        matcherNode.placeOrder(bobAcc, wctEarthsPair, SELL, wctEarthsSellAmount, wctEarthsPrice, matcherFee).message.id
      matcherNode.waitOrderStatus(wctEarthsPair, bobOrderId, "Accepted", 1.minute)

      matcherNode.tradableBalance(bobAcc, wctEarthsPair)("EARTHS") shouldBe matcherFee / 2 + receiveAmount(SELL, wctEarthsSellAmount, wctEarthsPrice) - matcherFee
      matcherNode.cancelOrder(bobAcc, wctEarthsPair, bobOrderId)

      assertBadRequestAndResponse(
        matcherNode.placeOrder(bobAcc, wctEarthsPair, SELL, wctEarthsSellAmount / 2, wctEarthsPrice, matcherFee),
        "Not enough tradable balance"
      )

      val cancelLeaseTxId = bobNode.cancelLease(bobAcc.address, leaseTxId, leasingFee, 2).id
      matcherNode.waitForTransaction(cancelLeaseTxId)
    }
  }

  "Alice and Bob trade ETH-EARTHS" - {
    "reserved balance is empty after the total execution" in {
      val counterId1 = matcherNode.placeOrder(aliceAcc, ethEarthsPair, SELL, 2864310, 300000, matcherFee).message.id
      matcherNode.waitOrderStatus(ethEarthsPair, counterId1, "Accepted", 1.minute)

      val counterId2 = matcherNode.placeOrder(aliceAcc, ethEarthsPair, SELL, 7237977, 300000, matcherFee).message.id
      matcherNode.waitOrderStatus(ethEarthsPair, counterId2, "Accepted", 1.minute)

      val submittedId = matcherNode.placeOrder(bobAcc, ethEarthsPair, BUY, 4373667, 300000, matcherFee).message.id

      matcherNode.waitOrderStatus(ethEarthsPair, counterId1, "Filled", 1.minute)
      matcherNode.waitOrderStatus(ethEarthsPair, counterId2, "PartiallyFilled", 1.minute)
      matcherNode.waitOrderStatus(ethEarthsPair, submittedId, "Filled", 1.minute)

      val exchangeTx = matcherNode.transactionsByOrder(submittedId).headOption.getOrElse(fail("Expected an exchange transaction"))
      matcherNode.waitForTransaction(exchangeTx.id)

      matcherNode.reservedBalance(bobAcc) shouldBe empty
      matcherNode.cancelOrder(aliceAcc, ethEarthsPair, counterId2)
    }
  }

  "Submitted order Canceled during match" in {
    val bobOrder   = matcherNode.prepareOrder(matcherAcc, earthsUsdPair, OrderType.SELL, 10000000L, 10L)
    val bobOrderId = matcherNode.placeOrder(bobOrder).message.id
    matcherNode.waitOrderStatus(earthsUsdPair, bobOrderId, "Accepted", 1.minute)

    val aliceOrder   = matcherNode.prepareOrder(aliceAcc, earthsUsdPair, OrderType.BUY, 100000L, 1000L)
    val aliceOrderId = matcherNode.placeOrder(aliceOrder).message.id

    matcherNode.waitOrderStatusAndAmount(earthsUsdPair, aliceOrderId, "Cancelled", Some(0), 1.minute)

    withClue("Alice's reserved balance:") {
      matcherNode.reservedBalance(aliceAcc) shouldBe empty
    }

    val aliceOrders = matcherNode.ordersByAddress(aliceAcc, activeOnly = false, 1.minute)
    aliceOrders should not be empty

    val order = aliceOrders.find(_.id == aliceOrderId).getOrElse(throw new IllegalStateException(s"Alice should have the $aliceOrderId order"))
    order.status shouldBe "Cancelled"

    matcherNode.cancelOrder(matcherAcc, earthsUsdPair, bobOrderId)
  }

  def correctAmount(a: Long, price: Long): Long = {
    val settledTotal = (BigDecimal(price) * a / Order.PriceConstant).setScale(0, RoundingMode.FLOOR).toLong
    (BigDecimal(settledTotal) / price * Order.PriceConstant).setScale(0, RoundingMode.CEILING).toLong
  }

  def receiveAmount(ot: OrderType, matchAmount: Long, matchPrice: Long): Long =
    if (ot == BUY) correctAmount(matchAmount, matchPrice)
    else {
      (BigInt(matchAmount) * matchPrice / Order.PriceConstant).bigInteger.longValueExact()
    }

}
