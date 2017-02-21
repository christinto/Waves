package scorex.transaction

import com.google.common.base.Charsets
import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.settings.WavesSettings
import play.api.libs.json.{JsArray, JsObject, Json}
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.api.http.assets._
import scorex.api.http.leasing.{LeaseCancelRequest, LeaseRequest}
import scorex.app.Application
import scorex.block.{Block, BlockField}
import scorex.consensus.TransactionsOrdering
import scorex.crypto.encode.Base58
import scorex.network.message.Message
import scorex.network.{Broadcast, NetworkController, TransactionalMessagesRepo}
import scorex.settings.ChainParameters
import scorex.transaction.ValidationError.{InvalidAddress, TransactionParameterValidationError, TransactionValidationError}
import scorex.transaction.assets.{BurnTransaction, _}
import scorex.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import scorex.transaction.state.database.{BlockStorageImpl, UnconfirmedTransactionsDatabaseImpl}
import scorex.transaction.state.wallet.{Payment, ReissueRequest}
import scorex.utils._
import scorex.wallet.Wallet
import scorex.waves.transaction.SignedPayment

import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Left, Right}

@SerialVersionUID(3044437555808662124L)
case class TransactionsBlockField(override val value: Seq[Transaction])
  extends BlockField[Seq[Transaction]] {

  import SimpleTransactionModule.MaxTransactionsPerBlock

  override val name = "transactions"

  override lazy val json: JsObject = Json.obj(name -> JsArray(value.map(_.json)))

  override lazy val bytes: Array[Byte] = {
    val txCount = value.size.ensuring(_ <= MaxTransactionsPerBlock).toByte
    value.foldLeft(Array(txCount)) { case (bs, tx) =>
      val txBytes = tx.bytes
      bs ++ Bytes.ensureCapacity(Ints.toByteArray(txBytes.length), 4, 0) ++ txBytes
    }
  }
}

class SimpleTransactionModule(hardForkParams: ChainParameters)(implicit val settings: WavesSettings,
                                                               application: Application)
  extends TransactionModule with TransactionOperations with ScorexLogging {

  import SimpleTransactionModule._

  val networkController = application.networkController
  private val feeCalculator = new FeeCalculator(settings.feesSettings)

  val InitialBalance = hardForkParams.initialBalance

  val utxStorage: UnconfirmedTransactionsStorage = new UnconfirmedTransactionsDatabaseImpl(settings.utxSettings)

  override val blockStorage = new BlockStorageImpl(settings.blockchainSettings)(application.consensusModule, this)

  override def unconfirmedTxs: Seq[Transaction] = utxStorage.all()

  override def putUnconfirmedIfNew[T <: Transaction](tx: T): Either[ValidationError, T] = synchronized {
    for {
      t1 <- feeCalculator.enoughFee(tx)
      t2 <- utxStorage.putIfNew(t1, (t: T) => validate(t))
    } yield t2
  }

  override def packUnconfirmed(heightOpt: Option[Int]): Seq[Transaction] = synchronized {
    clearIncorrectTransactions()

    val txs = utxStorage.all().sorted(TransactionsOrdering).take(MaxTransactionsPerBlock)
    val valid = blockStorage.state.validate(txs, heightOpt, NTP.correctedTime())._2

    if (valid.size != txs.size) {
      log.debug(s"Txs for new block do not match: valid=${valid.size} vs all=${txs.size}")
    }

    valid
  }

  override def clearFromUnconfirmed(data: Seq[Transaction]): Unit = synchronized {
    data.foreach(tx => utxStorage.getBySignature(tx.id) match {
      case Some(unconfirmedTx) => utxStorage.remove(unconfirmedTx)
      case None =>
    })

    clearIncorrectTransactions() // todo makes sence to remove expired only at this point
  }

  /**
    * Removes too old or invalid transactions from UnconfirmedTransactionsPool
    */
  def clearIncorrectTransactions(): Unit = {
    val currentTime = NTP.correctedTime()
    val txs = utxStorage.all()
    val notExpired = txs.filter { tx => (currentTime - tx.timestamp).millis <= MaxTimeForUnconfirmed }
    val notFromFuture = notExpired.filter { tx => (tx.timestamp - currentTime).millis <= MaxTimeDrift }
    val valid = blockStorage.state.validate(notFromFuture, blockTime = currentTime)._2
    // remove non valid or expired from storage
    txs.diff(valid).foreach(utxStorage.remove)
  }

  override def onNewOffchainTransaction[T <: Transaction](transaction: T): Either[ValidationError, T] =
    for {
      tx <- putUnconfirmedIfNew(transaction)
    } yield {
      val spec = TransactionalMessagesRepo.TransactionMessageSpec
      val ntwMsg = Message(spec, Right(transaction), None)
      networkController ! NetworkController.SendToNetwork(ntwMsg, Broadcast)
      tx
    }

  override def createPayment(payment: Payment, wallet: Wallet): Either[ValidationError, PaymentTransaction] = {
    createPayment(wallet.privateKeyAccount(payment.sender).get, new Account(payment.recipient), payment.amount, payment.fee)
  }

  override def transferAsset(request: TransferRequest, wallet: Wallet): Either[ValidationError, TransferTransaction] = {
    val sender = wallet.privateKeyAccount(request.sender).get
    TransferTransaction
      .create(request.assetId.map(s => Base58.decode(s).get),
        sender: PrivateKeyAccount,
        new Account(request.recipient),
        request.amount,
        getTimestamp,
        request.feeAssetId.map(s => Base58.decode(s).get),
        request.fee,
        request.attachment.filter(_.nonEmpty).map(Base58.decode(_).get).getOrElse(Array.emptyByteArray))
      .flatMap(onNewOffchainTransaction)
  }

  override def issueAsset(request: IssueRequest, wallet: Wallet): Either[ValidationError, IssueTransaction] = {
    val sender = wallet.privateKeyAccount(request.sender).get
    IssueTransaction
      .create(sender, request.name.getBytes(Charsets.UTF_8), request.description.getBytes(Charsets.UTF_8), request.quantity, request.decimals, request.reissuable, request.fee, getTimestamp)
      .flatMap(onNewOffchainTransaction)
  }

  def lease(request: LeaseRequest, wallet: Wallet): Either[ValidationError, LeaseTransaction] = {
    val sender = wallet.privateKeyAccount(request.sender).get
    LeaseTransaction.create(sender, request.amount, request.fee, getTimestamp, new Account(request.recipient))
      .flatMap(onNewOffchainTransaction)
  }

  def leaseCancel(request: LeaseCancelRequest, wallet: Wallet): Either[ValidationError, LeaseCancelTransaction] = {
    val sender = wallet.privateKeyAccount(request.sender).get
    LeaseCancelTransaction.create(sender, Base58.decode(request.txId).get, request.fee, getTimestamp)
      .flatMap(onNewOffchainTransaction)
  }

  override def reissueAsset(request: ReissueRequest, wallet: Wallet): Either[ValidationError, ReissueTransaction] = {
    val sender = wallet.privateKeyAccount(request.sender).get
    ReissueTransaction
      .create(sender, Base58.decode(request.assetId).get, request.quantity, request.reissuable, request.fee, getTimestamp)
      .flatMap(onNewOffchainTransaction)
  }

  override def burnAsset(request: BurnRequest, wallet: Wallet): Either[ValidationError, BurnTransaction] = {
    val sender = wallet.privateKeyAccount(request.sender).get
    BurnTransaction
      .create(sender, Base58.decode(request.assetId).get, request.quantity, request.fee, getTimestamp)
      .flatMap(onNewOffchainTransaction)
  }

  private var txTime: Long = 0

  private def getTimestamp: Long = synchronized {
    txTime = Math.max(NTP.correctedTime(), txTime + 1)
    txTime
  }

  override def createPayment(sender: PrivateKeyAccount, recipient: Account, amount: Long, fee: Long): Either[ValidationError, PaymentTransaction] =
    PaymentTransaction.create(sender, recipient, amount, fee, getTimestamp)
      .flatMap(onNewOffchainTransaction)


  override def genesisData: Seq[Transaction] = hardForkParams.genesisTxs

  /** Check whether tx is valid on current state and not expired yet
    */
  override def validate[T <: Transaction](tx: T): Either[ValidationError, T] = try {
    val notExpired = (blockStorage.history.lastBlock.timestamp - tx.timestamp).millis <= MaxTimeForUnconfirmed
    if (notExpired) {
      blockStorage.state.validate(tx, tx.timestamp)
    } else {
      Left(TransactionValidationError(tx, "Transaction expired in UTX Pool"))
    }
  } catch {
    case e: UnsupportedOperationException =>
      log.debug(s"DB can't find last block because of unexpected modification")
      Left(TransactionValidationError(tx, "DB can't find last block because of unexpected modification"))
    case NonFatal(t) =>
      log.error(s"Unexpected error during validation", t)
      throw t
  }

  override def isValid(block: Block): Boolean = try {
    val lastBlockTs = blockStorage.history.lastBlock.timestampField.value
    lazy val txsAreNew = block.transactionDataField.asInstanceOf[TransactionsBlockField].value.forall { tx => (lastBlockTs - tx.timestamp).millis <= MaxTxAndBlockDiff }
    lazy val (errors, validTrans) = blockStorage.state.validate(block.transactionData, blockStorage.history.heightOf(block), block.timestamp)
    if (!txsAreNew) log.debug(s"Invalid txs in block ${block.encodedId}: txs from the past")
    if (errors.nonEmpty) log.debug(s"Invalid txs in block ${block.encodedId}: not valid txs: $errors")
    txsAreNew && errors.isEmpty
  } catch {
    case e: UnsupportedOperationException =>
      log.debug(s"DB can't find last block because of unexpected modification")
      false
    case NonFatal(t) =>
      log.error(s"Unexpected error during validation", t)
      throw t
  }

  val minimumTxFee = 100000 // TODO: remove later

  override def signPayment(payment: Payment, wallet: Wallet): Either[ValidationError, PaymentTransaction] = {
    PaymentTransaction.create(wallet.privateKeyAccount(payment.sender).get, new Account(payment.recipient), payment.amount, payment.fee, NTP.correctedTime())
  }

  override def createSignedPayment(sender: PrivateKeyAccount, recipient: Account, amount: Long, fee: Long, timestamp: Long): Either[ValidationError, PaymentTransaction] = {

    for {
      p1 <- PaymentTransaction.create(sender, recipient, amount, fee, timestamp)
      p2 <- blockStorage.state.validate(p1, p1.timestamp)
    } yield p2
  }

  override def broadcastPayment(payment: SignedPayment): Either[ValidationError, PaymentTransaction] =
    for {
      _signature <- Base58.decode(payment.signature).toOption.toRight(ValidationError.InvalidSignature)
      _sender <- PublicKeyAccount.fromBase58String(payment.senderPublicKey)
      _t <- PaymentTransaction.create(_sender, new Account(payment.recipient), payment.amount, payment.fee, payment.timestamp, _signature)
      t <- onNewOffchainTransaction(_t)
    } yield t

}

object SimpleTransactionModule {
  val MaxTimeDrift: FiniteDuration = 15.seconds
  val MaxTimeForUnconfirmed: FiniteDuration = 90.minutes
  val MaxTxAndBlockDiff: FiniteDuration = 2.hour
  val MaxTransactionsPerBlock: Int = 100
}
