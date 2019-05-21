package encry.modifiers.mempool.directive

import TransactionProto.TransactionProtoMessage.DirectiveProtoMessage
import TransactionProto.TransactionProtoMessage.DirectiveProtoMessage.{AssetIssuingDirectiveProtoMessage, DirectiveProto}
import TransactionProto.TransactionProtoMessage.DirectiveProtoMessage.DirectiveProto.AssetIssuingDirectiveProto
import com.google.common.primitives.{Bytes, Ints, Longs}
import com.google.protobuf.ByteString
import encry.utils.CoreTaggedTypes.ModifierId
import encry.modifiers.mempool.directive.Directive.DTypeId
import encry.modifiers.state.box.Box.Amount
import encry.modifiers.state.box.{EncryBaseBox, EncryProposition, TokenIssuingBox}
import encry.settings.Constants
import encry.utils.Utils
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor}
import org.encryfoundation.common.serialization.Serializer
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.prismlang.compiler.CompiledContract.ContractHash
import scorex.crypto.encode.Base16
import scorex.crypto.hash.Digest32

import scala.util.Try

case class AssetIssuingDirective(contractHash: ContractHash, amount: Amount) extends Directive {

  override type M = AssetIssuingDirective
  override val typeId: DTypeId = AssetIssuingDirective.TypeId
  override lazy val isValid: Boolean = amount > 0

  override def boxes(digest: Digest32, idx: Int): Seq[EncryBaseBox] =
    Seq(TokenIssuingBox(
      EncryProposition(contractHash),
      Utils.nonceFromDigest(digest ++ Ints.toByteArray(idx)),
      amount,
      Algos.hash(Ints.toByteArray(idx) ++ digest)
    ))

  override def serializer: Serializer[M] = AssetIssuingDirectiveSerializer

  override def toDbVersion(txId: ModifierId, numberInTx: Int): DirectiveDBVersion =
    DirectiveDBVersion(Base16.encode(txId), numberInTx, typeId, isValid, Base16.encode(contractHash), amount, "", None, "")

  override def toDirectiveProto: DirectiveProtoMessage = AssetIssuingDirectiveProtoSerializer.toProto(this)
}

object AssetIssuingDirective {

  val TypeId: DTypeId = 2.toByte

  implicit val jsonEncoder: Encoder[AssetIssuingDirective] = (d: AssetIssuingDirective) => Map(
    "typeId" -> d.typeId.asJson,
    "contractHash" -> Algos.encode(d.contractHash).asJson,
    "amount" -> d.amount.asJson
  ).asJson

  implicit val jsonDecoder: Decoder[AssetIssuingDirective] = (c: HCursor) => {
    for {
      contractHash <- c.downField("contractHash").as[String]
      amount <- c.downField("amount").as[Long]
    } yield Algos.decode(contractHash)
      .map(ch => AssetIssuingDirective(ch, amount))
      .getOrElse(throw new Exception("Decoding failed"))
  }
}

object AssetIssuingDirectiveProtoSerializer extends ProtoDirectiveSerializer[AssetIssuingDirective] {

  override def toProto(message: AssetIssuingDirective): DirectiveProtoMessage =
    DirectiveProtoMessage().withAssetIssuingDirectiveProto(AssetIssuingDirectiveProtoMessage()
      .withAmount(message.amount)
      .withContractHash(ByteString.copyFrom(message.contractHash))
    )

  override def fromProto(message: DirectiveProtoMessage): Option[AssetIssuingDirective] =
    message.directiveProto.assetIssuingDirectiveProto match {
      case Some(value) => Some(AssetIssuingDirective(value.contractHash.toByteArray, value.amount))
      case None => Option.empty[AssetIssuingDirective]
    }
}

object AssetIssuingDirectiveSerializer extends Serializer[AssetIssuingDirective] {

  override def toBytes(obj: AssetIssuingDirective): Array[Byte] =
    Bytes.concat(
      obj.contractHash,
      Longs.toByteArray(obj.amount)
    )

  override def parseBytes(bytes: Array[Byte]): Try[AssetIssuingDirective] = Try {
    val contractHash: ContractHash = bytes.take(Constants.DigestLength)
    val amount: Amount = Longs.fromByteArray(bytes.slice(Constants.DigestLength, Constants.DigestLength + 8))
    AssetIssuingDirective(contractHash, amount)
  }
}