package com.earthspay.state.diffs

import cats._
import com.earthspay.account.Address
import com.earthspay.features.FeatureProvider._
import com.earthspay.features.{BlockchainFeature, BlockchainFeatures}
import com.earthspay.lang.StdLibVersion._
import com.earthspay.settings.FunctionalitySettings
import com.earthspay.state._
import com.earthspay.transaction.ValidationError._
import com.earthspay.transaction.assets._
import com.earthspay.transaction.assets.exchange._
import com.earthspay.transaction.lease._
import com.earthspay.transaction.smart.script.ContractScript
import com.earthspay.transaction.smart.script.Script
import com.earthspay.transaction.smart.script.v1.ExprScript.ExprScriprImpl
import com.earthspay.transaction.smart.{ContractInvocationTransaction, SetScriptTransaction}
import com.earthspay.transaction.transfer._
import com.earthspay.transaction.{smart, _}

import scala.util.{Left, Right}

object CommonValidation {

  val ScriptExtraFee = 400000L
  val FeeUnit        = 100000

  val FeeConstants: Map[Byte, Long] = Map(
    GenesisTransaction.typeId                  -> 0,
    PaymentTransaction.typeId                  -> 1,
    IssueTransaction.typeId                    -> 1000,
    ReissueTransaction.typeId                  -> 1000,
    BurnTransaction.typeId                     -> 1,
    TransferTransaction.typeId                 -> 1,
    MassTransferTransaction.typeId             -> 1,
    LeaseTransaction.typeId                    -> 1,
    LeaseCancelTransaction.typeId              -> 1,
    ExchangeTransaction.typeId                 -> 3,
    CreateAliasTransaction.typeId              -> 1,
    DataTransaction.typeId                     -> 1,
    SetScriptTransaction.typeId                -> 10,
    SponsorFeeTransaction.typeId               -> 1000,
    SetAssetScriptTransaction.typeId           -> (1000 - 4),
    smart.ContractInvocationTransaction.typeId -> 5
  )

  def disallowSendingGreaterThanBalance[T <: Transaction](blockchain: Blockchain,
                                                          settings: FunctionalitySettings,
                                                          blockTime: Long,
                                                          tx: T): Either[ValidationError, T] =
    if (blockTime >= settings.allowTemporaryNegativeUntil) {
      def checkTransfer(sender: Address, assetId: Option[AssetId], amount: Long, feeAssetId: Option[AssetId], feeAmount: Long) = {
        val amountDiff = assetId match {
          case Some(aid) => Portfolio(0, LeaseBalance.empty, Map(aid -> -amount))
          case None      => Portfolio(-amount, LeaseBalance.empty, Map.empty)
        }
        val feeDiff = feeAssetId match {
          case Some(aid) => Portfolio(0, LeaseBalance.empty, Map(aid -> -feeAmount))
          case None      => Portfolio(-feeAmount, LeaseBalance.empty, Map.empty)
        }

        val spendings       = Monoid.combine(amountDiff, feeDiff)
        val oldEarthsBalance = blockchain.balance(sender, None)

        val newEarthsBalance = oldEarthsBalance + spendings.balance
        if (newEarthsBalance < 0) {
          Left(
            GenericError(
              "Attempt to transfer unavailable funds: Transaction application leads to " +
                s"negative earths balance to (at least) temporary negative state, current balance equals $oldEarthsBalance, " +
                s"spends equals ${spendings.balance}, result is $newEarthsBalance"))
        } else {
          val balanceError = spendings.assets.collectFirst {
            case (aid, delta) if delta < 0 && blockchain.balance(sender, Some(aid)) + delta < 0 =>
              val availableBalance = blockchain.balance(sender, Some(aid))
              GenericError(
                "Attempt to transfer unavailable funds: Transaction application leads to negative asset " +
                  s"'$aid' balance to (at least) temporary negative state, current balance is $availableBalance, " +
                  s"spends equals $delta, result is ${availableBalance + delta}")
          }
          balanceError.fold[Either[ValidationError, T]](Right(tx))(Left(_))
        }
      }

      tx match {
        case ptx: PaymentTransaction if blockchain.balance(ptx.sender, None) < (ptx.amount + ptx.fee) =>
          Left(
            GenericError(
              "Attempt to pay unavailable funds: balance " +
                s"${blockchain.balance(ptx.sender, None)} is less than ${ptx.amount + ptx.fee}"))
        case ttx: TransferTransaction     => checkTransfer(ttx.sender, ttx.assetId, ttx.amount, ttx.feeAssetId, ttx.fee)
        case mtx: MassTransferTransaction => checkTransfer(mtx.sender, mtx.assetId, mtx.transfers.map(_.amount).sum, None, mtx.fee)
        case _                            => Right(tx)
      }
    } else Right(tx)

  def disallowDuplicateIds[T <: Transaction](blockchain: Blockchain,
                                             settings: FunctionalitySettings,
                                             height: Int,
                                             tx: T): Either[ValidationError, T] = tx match {
    case _: PaymentTransaction => Right(tx)
    case _ =>
      val id = tx.id()
      Either.cond(!blockchain.containsTransaction(tx), tx, AlreadyInTheState(id, blockchain.transactionInfo(id).get._1))
  }

  def disallowBeforeActivationTime[T <: Transaction](blockchain: Blockchain, height: Int, tx: T): Either[ValidationError, T] = {

    def activationBarrier(b: BlockchainFeature, msg: Option[String] = None) =
      Either.cond(
        blockchain.isFeatureActivated(b, height),
        tx,
        ValidationError.ActivationError(msg.getOrElse(tx.getClass.getSimpleName) + " has not been activated yet")
      )

    def scriptActivation(sc: Script) = {
      val ab = activationBarrier(BlockchainFeatures.Ride4DApps, Some("Ride4DApps"))
      def scriptVersionActivation(sc: Script) = sc.stdLibVersion match {
        case V1 | V2 if sc.containsBlockV2.value => ab
        case V1 | V2                             => Right(tx)
        case V3                                  => ab
      }
      def scriptTypeActivation(sc: Script) = sc match {
        case e: ExprScriprImpl                    => Right(tx)
        case c: ContractScript.ContractScriptImpl => ab
      }
      for {
        _ <- scriptVersionActivation(sc)
        _ <- scriptTypeActivation(sc)
      } yield tx

    }
    tx match {
      case _: BurnTransactionV1        => Right(tx)
      case _: PaymentTransaction       => Right(tx)
      case _: GenesisTransaction       => Right(tx)
      case _: TransferTransactionV1    => Right(tx)
      case _: IssueTransactionV1       => Right(tx)
      case _: ReissueTransactionV1     => Right(tx)
      case _: ExchangeTransactionV1    => Right(tx)
      case _: ExchangeTransactionV2    => activationBarrier(BlockchainFeatures.SmartAccountTrading)
      case _: LeaseTransactionV1       => Right(tx)
      case _: LeaseCancelTransactionV1 => Right(tx)
      case _: CreateAliasTransactionV1 => Right(tx)
      case _: MassTransferTransaction  => activationBarrier(BlockchainFeatures.MassTransfer)
      case _: DataTransaction          => activationBarrier(BlockchainFeatures.DataTransaction)
      case sst: SetScriptTransaction =>
        sst.script match {
          case None     => Right(tx)
          case Some(sc) => scriptActivation(sc)
        }
      case _: TransferTransactionV2 => activationBarrier(BlockchainFeatures.SmartAccounts)
      case it: IssueTransactionV2   => activationBarrier(if (it.script.isEmpty) BlockchainFeatures.SmartAccounts else BlockchainFeatures.SmartAssets)
      case it: SetAssetScriptTransaction =>
        it.script match {
          case None     => Left(GenericError("Cannot set empty script"))
          case Some(sc) => scriptActivation(sc)
        }

      case _: ReissueTransactionV2          => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: BurnTransactionV2             => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: LeaseTransactionV2            => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: LeaseCancelTransactionV2      => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: CreateAliasTransactionV2      => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: SponsorFeeTransaction         => activationBarrier(BlockchainFeatures.FeeSponsorship)
      case _: ContractInvocationTransaction => activationBarrier(BlockchainFeatures.Ride4DApps)
      case _                                => Left(GenericError("Unknown transaction must be explicitly activated"))
    }
  }

  def disallowTxFromFuture[T <: Transaction](settings: FunctionalitySettings, time: Long, tx: T): Either[ValidationError, T] = {
    val allowTransactionsFromFutureByTimestamp = tx.timestamp < settings.allowTransactionsFromFutureUntil
    if (!allowTransactionsFromFutureByTimestamp && tx.timestamp - time > settings.maxTransactionTimeForwardOffset.toMillis)
      Left(Mistiming(s"""Transaction timestamp ${tx.timestamp}
       |is more than ${settings.maxTransactionTimeForwardOffset.toMillis}ms in the future
       |relative to block timestamp $time""".stripMargin.replaceAll("\n", " ")))
    else Right(tx)
  }

  def disallowTxFromPast[T <: Transaction](settings: FunctionalitySettings, prevBlockTime: Option[Long], tx: T): Either[ValidationError, T] =
    prevBlockTime match {
      case Some(t) if (t - tx.timestamp) > settings.maxTransactionTimeBackOffset.toMillis =>
        Left(Mistiming(s"""Transaction timestamp ${tx.timestamp}
         |is more than ${settings.maxTransactionTimeBackOffset.toMillis}ms in the past
         |relative to previous block timestamp $prevBlockTime""".stripMargin.replaceAll("\n", " ")))
      case _ => Right(tx)
    }

  private def feeInUnits(blockchain: Blockchain, height: Int, tx: Transaction): Either[ValidationError, Long] = {
    FeeConstants
      .get(tx.builder.typeId)
      .map { baseFee =>
        tx match {
          case tx: MassTransferTransaction =>
            baseFee + (tx.transfers.size + 1) / 2
          case tx: DataTransaction =>
            val base = if (blockchain.isFeatureActivated(BlockchainFeatures.SmartAccounts, height)) tx.bodyBytes() else tx.bytes()
            baseFee + (base.length - 1) / 1024
          case _ => baseFee
        }
      }
      .toRight(UnsupportedTransactionType)
  }

  def getMinFee(blockchain: Blockchain,
                fs: FunctionalitySettings,
                height: Int,
                tx: Transaction): Either[ValidationError, (Option[AssetId], Long, Long)] = {
    type FeeInfo = (Option[(AssetId, AssetDescription)], Long)

    def feeAfterSponsorship(txAsset: Option[AssetId]): Either[ValidationError, FeeInfo] =
      if (height < Sponsorship.sponsoredFeesSwitchHeight(blockchain, fs)) {
        // This could be true for private blockchains
        feeInUnits(blockchain, height, tx).map(x => (None, x * FeeUnit))
      } else
        for {
          feeInUnits <- feeInUnits(blockchain, height, tx)
          r <- txAsset match {
            case None => Right((None, feeInUnits * FeeUnit))
            case Some(assetId) =>
              for {
                assetInfo <- blockchain.assetDescription(assetId).toRight(GenericError(s"Asset $assetId does not exist, cannot be used to pay fees"))
                earthsFee <- Either.cond(
                  assetInfo.sponsorship > 0,
                  feeInUnits * FeeUnit,
                  GenericError(s"Asset $assetId is not sponsored, cannot be used to pay fees")
                )
              } yield (Some((assetId, assetInfo)), earthsFee)
          }
        } yield r

    def isSmartToken(input: FeeInfo): Boolean = input._1.map(_._1).flatMap(blockchain.assetDescription).exists(_.script.isDefined)

    def feeAfterSmartTokens(inputFee: FeeInfo): Either[ValidationError, FeeInfo] = {
      val (feeAssetInfo, feeAmount) = inputFee
      val assetsCount = tx match {
        case tx: ExchangeTransaction => tx.checkedAssets().count(blockchain.hasAssetScript) /* *3 if we deside to check orders and transaction */
        case _                       => tx.checkedAssets().count(blockchain.hasAssetScript)
      }
      if (isSmartToken(inputFee)) {
        //Left(GenericError("Using smart asset for sponsorship is disabled."))
        Right { (feeAssetInfo, feeAmount + ScriptExtraFee * (1 + assetsCount)) }
      } else {
        Right { (feeAssetInfo, feeAmount + ScriptExtraFee * assetsCount) }
      }
    }

    def smartAccountScriptsCount: Int = tx match {
      case tx: Transaction with Authorized => cond(blockchain.hasScript(tx.sender))(1, 0)
      case _                               => 0
    }

    def feeAfterSmartAccounts(inputFee: FeeInfo): Either[ValidationError, FeeInfo] = Right {
      val extraFee                  = smartAccountScriptsCount * ScriptExtraFee
      val (feeAssetInfo, feeAmount) = inputFee
      (feeAssetInfo, feeAmount + extraFee)
    }

    feeAfterSponsorship(tx.assetFee._1)
      .flatMap(feeAfterSmartTokens)
      .flatMap(feeAfterSmartAccounts)
      .map {
        case (Some((assetId, assetInfo)), amountInEarths) =>
          (Some(assetId), Sponsorship.fromEarths(amountInEarths, assetInfo.sponsorship), amountInEarths)
        case (None, amountInEarths) => (None, amountInEarths, amountInEarths)
      }
  }

  def checkFee(blockchain: Blockchain, fs: FunctionalitySettings, height: Int, tx: Transaction): Either[ValidationError, Unit] = {
    if (height >= Sponsorship.sponsoredFeesSwitchHeight(blockchain, fs)) {
      for {
        minAFee <- getMinFee(blockchain, fs, height, tx)
        minEarths   = minAFee._3
        minFee     = minAFee._2
        feeAssetId = minAFee._1
        _ <- Either.cond(
          minFee <= tx.assetFee._2,
          (),
          GenericError(
            s"Fee in ${feeAssetId.fold("EARTHS")(_.toString)} for ${tx.builder.classTag} does not exceed minimal value of $minEarths EARTHS: ${tx.assetFee._2}")
        )
      } yield ()
    } else {
      Either.cond(tx.assetFee._2 > 0 || !tx.isInstanceOf[Authorized], (), GenericError(s"Fee must be positive."))
    }
  }

  def cond[A](c: Boolean)(a: A, b: A): A = if (c) a else b
}
