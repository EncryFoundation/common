package org.encryfoundation.common.modifiers.mempool.transaction

import TransactionProto.TransactionProtoMessage
import io.circe.{ Decoder, HCursor }
import io.circe.syntax._
import org.encryfoundation.prismlang.core.Types
import io.circe.Encoder
import org.encryfoundation.prismlang.core.PConvertible
import scorex.crypto.hash.Digest32

import scala.util.Try
import com.google.common.primitives.{ Bytes, Ints, Longs, Shorts }
import com.google.protobuf.ByteString
import org.encryfoundation.common.modifiers.NodeViewModifier
import org.encryfoundation.common.modifiers.mempool.directive.{
  Directive,
  DirectiveProtoSerializer,
  DirectiveSerializer
}
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox
import org.encryfoundation.common.serialization.Serializer
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{ ModifierId, ModifierTypeId }
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.encryfoundation.common.validation.{ ModifierValidator, ValidationResult }
import org.encryfoundation.prismlang.core.wrapped.{ PObject, PValue }

case class Transaction(fee: Amount,
                       timestamp: Long,
                       inputs: IndexedSeq[Input],
                       directives: IndexedSeq[Directive],
                       defaultProofOpt: Option[Proof])
    extends NodeViewModifier
    with ModifierValidator
    with PConvertible {

  override val modifierTypeId: ModifierTypeId = Transaction.modifierTypeId

  override type M = Transaction

  override def serializer: Serializer[Transaction] = TransactionSerializer

  def toTransactionProto: TransactionProtoMessage = TransactionProtoSerializer.toProto(this)

  val messageToSign: Array[Byte] = UnsignedTransaction.bytesToSign(fee, timestamp, inputs, directives)

  val id: ModifierId = ModifierId !@@ Algos.hash(messageToSign)

  lazy val size: Int = this.bytes.length

  val newBoxes: Traversable[EncryBaseBox] =
    directives.zipWithIndex.flatMap { case (d, idx) => d.boxes(Digest32 !@@ id, idx) }

  override def toString: String =
    s"<Transaction id=${Algos.encode(id)}\nfee=$fee\ninputs=$inputs\ndirectives=$directives\nts=$timestamp\nproofs=$defaultProofOpt>"

  //todo: Add validation of timestamp without using timeProvider from EncryApp
  lazy val semanticValidity: ValidationResult = accumulateErrors
    .demand(fee >= 0, "Negative fee amount")
    .demand(inputs.lengthCompare(inputs.toSet.size) == 0, "Inputs duplication")
    .demand(inputs.lengthCompare(Short.MaxValue) <= 0, "Wrong number of inputs")
    .demand(directives.lengthCompare(Short.MaxValue) <= 0 && directives.nonEmpty, "Wrong number of directives")
    .demand(directives.forall(_.isValid), "Invalid outputs")
    .result

  val tpe: Types.Product = Types.EncryTransaction

  override def hashCode(): Int = Ints.fromByteArray(messageToSign.take(4))

  def asVal: PValue =
    PValue(
      PObject(
        Map(
          "inputs"        -> PValue(inputs.map(_.boxId.toList), Types.PCollection(Types.PCollection.ofByte)),
          "outputs"       -> PValue(newBoxes.map(_.asPrism), Types.PCollection(Types.EncryBox)),
          "messageToSign" -> PValue(messageToSign, Types.PCollection.ofByte)
        ),
        tpe
      ),
      tpe
    )
}

object Transaction {

  val modifierTypeId: ModifierTypeId = ModifierTypeId @@ 2.toByte

  case class TransactionValidationException(s: String) extends Exception(s)

  implicit val jsonEncoder: Encoder[Transaction] = (tx: Transaction) =>
    Map(
      "id"              -> Algos.encode(tx.id).asJson,
      "fee"             -> tx.fee.asJson,
      "timestamp"       -> tx.timestamp.asJson,
      "inputs"          -> tx.inputs.map(_.asJson).asJson,
      "directives"      -> tx.directives.map(_.asJson).asJson,
      "outputs"         -> tx.newBoxes.toSeq.map(_.asJson).asJson,
      "defaultProofOpt" -> tx.defaultProofOpt.map(_.asJson).asJson
    ).asJson

  implicit val jsonDecoder: Decoder[Transaction] = (c: HCursor) =>
    for {
      fee             <- c.downField("fee").as[Long]
      timestamp       <- c.downField("timestamp").as[Long]
      inputs          <- c.downField("inputs").as[IndexedSeq[Input]]
      directives      <- c.downField("directives").as[IndexedSeq[Directive]]
      defaultProofOpt <- c.downField("defaultProofOpt").as[Option[Proof]]
    } yield
      Transaction(
        fee,
        timestamp,
        inputs,
        directives,
        defaultProofOpt
    )
}

trait ProtoTransactionSerializer[T] {

  def toProto(message: T): TransactionProtoMessage

  def fromProto(message: TransactionProtoMessage): Try[T]
}

object TransactionProtoSerializer extends ProtoTransactionSerializer[Transaction] {

  override def toProto(message: Transaction): TransactionProtoMessage = {
    val initialTx: TransactionProtoMessage = TransactionProtoMessage()
      .withFee(message.fee)
      .withTimestamp(message.timestamp)
      .withInputs(
        message.inputs.map(input => ByteString.copyFrom(input.bytes)).to[scala.collection.immutable.IndexedSeq]
      )
      .withDirectives(message.directives.map(_.toDirectiveProto).to[scala.collection.immutable.IndexedSeq])
    message.defaultProofOpt match {
      case Some(value) => initialTx.withProof(ByteString.copyFrom(value.bytes))
      case None        => initialTx
    }
  }

  override def fromProto(message: TransactionProtoMessage): Try[Transaction] =
    Try(
      Transaction(
        message.fee,
        message.timestamp,
        message.inputs.map(element => InputSerializer.parseBytes(element.toByteArray).get),
        message.directives.map(directive => DirectiveProtoSerializer.fromProto(directive).get),
        ProofSerializer.parseBytes(message.proof.toByteArray).toOption
      )
    )
}

object TransactionSerializer extends Serializer[Transaction] {

  case object SerializationException extends Exception("Serialization failed.")

  override def toBytes(obj: Transaction): Array[Byte] =
    Bytes.concat(
      Longs.toByteArray(obj.fee),
      Longs.toByteArray(obj.timestamp),
      Shorts.toByteArray(obj.inputs.size.toShort),
      Shorts.toByteArray(obj.directives.size.toShort),
      obj.inputs.map(u => Shorts.toByteArray(u.bytes.length.toShort) ++ u.bytes).foldLeft(Array[Byte]())(_ ++ _),
      obj.directives.map { d =>
        val bytes: Array[Byte] = DirectiveSerializer.toBytes(d)
        Shorts.toByteArray(bytes.length.toShort) ++ bytes
      }.reduceLeft(_ ++ _),
      obj.defaultProofOpt.map(p => ProofSerializer.toBytes(p)).getOrElse(Array.empty)
    )

  override def parseBytes(bytes: Array[Byte]): Try[Transaction] = Try {
    val fee: Amount             = Longs.fromByteArray(bytes.take(8))
    val timestamp: Amount       = Longs.fromByteArray(bytes.slice(8, 16))
    val unlockersQty: Int       = Shorts.fromByteArray(bytes.slice(16, 18))
    val directivesQty: Int      = Shorts.fromByteArray(bytes.slice(18, 20))
    val leftBytes1: Array[Byte] = bytes.drop(20)
    val (unlockers: IndexedSeq[Input], unlockersLen: Int) = (0 until unlockersQty)
      .foldLeft(IndexedSeq[Input](), 0) {
        case ((acc, shift), _) =>
          val len: Int = Shorts.fromByteArray(leftBytes1.slice(shift, shift + 2))
          InputSerializer
            .parseBytes(leftBytes1.slice(shift + 2, shift + 2 + len))
            .map(u => (acc :+ u, shift + 2 + len))
            .getOrElse(throw SerializationException)
      }
    val leftBytes2: Array[Byte] = leftBytes1.drop(unlockersLen)
    val (directives: IndexedSeq[Directive], directivesLen: Int) = (0 until directivesQty)
      .foldLeft(IndexedSeq[Directive](), 0) {
        case ((acc, shift), _) =>
          val len: Int = Shorts.fromByteArray(leftBytes2.slice(shift, shift + 2))
          DirectiveSerializer
            .parseBytes(leftBytes2.slice(shift + 2, shift + 2 + len))
            .map(d => (acc :+ d, shift + 2 + len))
            .getOrElse(throw SerializationException)
      }
    val proofOpt: Option[Proof] =
      if (leftBytes2.length - directivesLen == 0) None
      else {
        ProofSerializer
          .parseBytes(leftBytes2.drop(directivesLen))
          .map(Some(_))
          .getOrElse(throw SerializationException)
      }
    Transaction(fee, timestamp, unlockers, directives, proofOpt)
  }

}

case class UnsignedTransaction(fee: Amount,
                               timestamp: Long,
                               inputs: IndexedSeq[Input],
                               directives: IndexedSeq[Directive]) {

  val messageToSign: Array[Byte] = UnsignedTransaction.bytesToSign(fee, timestamp, inputs, directives)

  def toSigned(proofs: IndexedSeq[Seq[Proof]], defaultProofOpt: Option[Proof]): Transaction = {
    val signedInputs: IndexedSeq[Input] = inputs.zipWithIndex.map {
      case (input, idx) =>
        if (proofs.nonEmpty && proofs.lengthCompare(idx + 1) <= 0) input.copy(proofs = proofs(idx).toList) else input
    }
    Transaction(fee, timestamp, signedInputs, directives, defaultProofOpt)
  }
}

object UnsignedTransaction {

  def bytesToSign(fee: Amount,
                  timestamp: Long,
                  unlockers: IndexedSeq[Input],
                  directives: IndexedSeq[Directive]): Digest32 =
    Algos.hash(
      Bytes.concat(
        unlockers.map(_.bytesWithoutProof).foldLeft(Array[Byte]())(_ ++ _),
        directives.map(_.bytes).foldLeft(Array[Byte]())(_ ++ _),
        Longs.toByteArray(timestamp),
        Longs.toByteArray(fee)
      )
    )
}
