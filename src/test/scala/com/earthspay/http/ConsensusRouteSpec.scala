package com.earthspay.http

import akka.http.scaladsl.server.Route
import com.earthspay.BlockGen
import com.earthspay.api.http.BlockDoesNotExist
import com.earthspay.consensus.nxt.api.http.NxtConsensusApiRoute
import com.earthspay.db.WithDomain
import com.earthspay.http.ApiMarshallers._
import com.earthspay.settings.FunctionalitySettings
import com.earthspay.state._
import org.scalatest.prop.PropertyChecks
import play.api.libs.json.JsObject

class ConsensusRouteSpec
    extends RouteSpec("/consensus")
    with RestAPISettingsHelper
    with PropertyChecks
    with BlockGen
    with HistoryTest
    with WithDomain {

  def routeTest(f: (Blockchain, Route) => Any) = withDomain() { d =>
    d.blockchainUpdater.processBlock(genesisBlock)
    1 to 10 foreach { _ =>
      d.blockchainUpdater.processBlock(getNextTestBlock(d.blockchainUpdater))
    }
    f(d.blockchainUpdater, NxtConsensusApiRoute(restAPISettings, d.blockchainUpdater, FunctionalitySettings.TESTNET).route)
  }

  routePath("/generationsignature") - {
    "for last block" in routeTest { (h, route) =>
      Get(routePath("/generationsignature")) ~> route ~> check {
        (responseAs[JsObject] \ "generationSignature").as[String] shouldEqual h.lastBlock.get.consensusData.generationSignature.base58
      }
    }

    "for existing block" in routeTest { (h, route) =>
      val block = h.blockAt(3).get
      Get(routePath(s"/generationsignature/${block.uniqueId.base58}")) ~> route ~> check {
        (responseAs[JsObject] \ "generationSignature").as[String] shouldEqual block.consensusData.generationSignature.base58
      }
    }

    "for non-existent block" in routeTest { (h, route) =>
      Get(routePath(s"/generationsignature/brggwg4wg4g")) ~> route should produce(BlockDoesNotExist)
    }
  }

  routePath("/basetarget") - {
    "for existing block" in routeTest { (h, route) =>
      val block = h.blockAt(3).get
      Get(routePath(s"/basetarget/${block.uniqueId.base58}")) ~> route ~> check {
        (responseAs[JsObject] \ "baseTarget").as[Long] shouldEqual block.consensusData.baseTarget
      }
    }

    "for non-existent block" in routeTest { (h, route) =>
      Get(routePath(s"/basetarget/brggwg4wg4g")) ~> route should produce(BlockDoesNotExist)
    }
  }
}
