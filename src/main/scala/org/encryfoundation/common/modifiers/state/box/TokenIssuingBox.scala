package org.encryfoundation.common.modifiers.state.box

import BoxesProto.BoxProtoMessage
import BoxesProto.BoxProtoMessage.TokenIssuingBoxProtoMessage
import com.google.common.primitives.{ Bytes, Longs, Shorts }
import com.google.protobuf.ByteString
import io.circe.{ Decoder, Encoder, HCursor }
import io.circe.syntax._
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.modifiers.state.box.EncryBox.BxTypeId
import org.encryfoundation.common.modifiers.state.box.TokenIssuingBox.TokenId
import org.encryfoundation.common.serialization.Serializer
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.prismlang.core.Types
import org.encryfoundation.prismlang.core.wrapped.{ PObject, PValue }

import scala.util.Try

case class TokenIssuingBox(override val proposition: EncryProposition,
                           override val nonce: Long,
                           override val amount: Amount,
                           tokenId: TokenId)
    extends EncryBox[EncryProposition]
    with MonetaryBox {

  override type M = TokenIssuingBox

  override val typeId: BxTypeId = TokenIssuingBox.modifierTypeId

  override def serializer: Serializer[M] = AssetIssuingBoxSerializer

  override val tpe: Types.Product = Types.AssetIssuingBox

  override def asVal: PValue = PValue(asPrism, Types.DataBox)

  override def asPrism: PObject = PObject(baseFields ++ Map("amount" -> PValue(amount, Types.PInt)), tpe)

  override def serializeToProto: BoxProtoMessage = TokenIssuingBoxProtoSerializer.toProto(this)
}

object TokenIssuingBox {

  type TokenId = Array[Byte]

  val modifierTypeId: BxTypeId = 3.toByte

  implicit val jsonEncoder: Encoder[TokenIssuingBox] = (bx: TokenIssuingBox) =>
    Map(
      "type"        -> modifierTypeId.asJson,
      "id"          -> Algos.encode(bx.id).asJson,
      "tokenId"     -> Algos.encode(bx.tokenId).asJson,
      "proposition" -> bx.proposition.asJson,
      "nonce"       -> bx.nonce.asJson,
      "amount"      -> bx.amount.asJson
    ).asJson

  implicit val jsonDecoder: Decoder[TokenIssuingBox] = (c: HCursor) =>
    for {
      proposition <- c.downField("proposition").as[EncryProposition]
      nonce       <- c.downField("nonce").as[Long]
      amount      <- c.downField("amount").as[Long]
      tokenId     <- c.downField("tokenId").as[String]
    } yield
      TokenIssuingBox(
        proposition,
        nonce,
        amount,
        Algos.decode(tokenId).getOrElse(Array.emptyByteArray)
    )

}

object TokenIssuingBoxProtoSerializer extends BaseBoxProtoSerialize[TokenIssuingBox] {

  override def toProto(t: TokenIssuingBox): BoxProtoMessage =
    BoxProtoMessage().withTokenIssuingBox(
      TokenIssuingBoxProtoMessage()
        .withPropositionProtoMessage(ByteString.copyFrom(t.proposition.contractHash))
        .withNonce(t.nonce)
        .withAmount(t.amount)
        .withTokenId(ByteString.copyFrom(t.tokenId))
    )

  override def fromProto(b: Array[Byte]): Try[TokenIssuingBox] = Try {
    val box: BoxProtoMessage = BoxProtoMessage.parseFrom(b)
    TokenIssuingBox(
      EncryProposition(box.getTokenIssuingBox.propositionProtoMessage.toByteArray),
      box.getTokenIssuingBox.nonce,
      box.getTokenIssuingBox.amount,
      box.getTokenIssuingBox.tokenId.toByteArray
    )
  }
}

object AssetIssuingBoxSerializer extends Serializer[TokenIssuingBox] {

  override def toBytes(obj: TokenIssuingBox): Array[Byte] = {
    val propBytes: Array[BxTypeId] = EncryPropositionSerializer.toBytes(obj.proposition)
    Bytes.concat(
      Shorts.toByteArray(propBytes.length.toShort),
      propBytes,
      Longs.toByteArray(obj.nonce),
      Longs.toByteArray(obj.amount),
      obj.tokenId
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[TokenIssuingBox] = Try {
    val propositionLen: Short         = Shorts.fromByteArray(bytes.take(2))
    val iBytes: Array[BxTypeId]       = bytes.drop(2)
    val proposition: EncryProposition = EncryPropositionSerializer.parseBytes(iBytes.take(propositionLen)).get
    val nonce: Amount                 = Longs.fromByteArray(iBytes.slice(propositionLen, propositionLen + 8))
    val amount: Amount                = Longs.fromByteArray(iBytes.slice(propositionLen + 8, propositionLen + 8 + 8))
    val creationId: TokenId           = bytes.takeRight(TestNetConstants.ModifierIdSize)
    TokenIssuingBox(proposition, nonce, amount, creationId)
  }
}
