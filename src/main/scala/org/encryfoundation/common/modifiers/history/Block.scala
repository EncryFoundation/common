package org.encryfoundation.common.modifiers.history

import BlockProto.BlockProtoMessage
import com.google.common.primitives.{Bytes, Ints}
import io.circe.{Decoder, Encoder, HCursor}
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.serialization.Serializer
import org.encryfoundation.common.utils.TaggedTypes.{ModifierId, ModifierTypeId}
import org.encryfoundation.common.validation.ModifierValidator
import io.circe.syntax._
import org.encryfoundation.common.modifiers.mempool.directive.TransferDirective
import scorex.crypto.encode.Base16
import scala.util.Try

case class Block(header: Header,
                 payload: Payload,
                 adProofsOpt: Option[ADProofs]) extends PersistentModifier with ModifierValidator {

  override type M = Block

  override val modifierTypeId: ModifierTypeId = Block.modifierTypeId

  override lazy val id: ModifierId = header.id

  override def parentId: ModifierId = header.parentId

  override def serializer: Serializer[Block] = BlockSerializer

  override def toString: String = {
    val encodedId: String = Base16.encode(id)
    val encodedParentId: String = Base16.encode(parentId)
    val proofsRoot: String = Base16.encode(header.adProofsRoot)
    val stateRoot: String = Base16.encode(header.stateRoot)
    val transactionsRoot: String = Base16.encode(header.transactionsRoot)
    val proofs: String = adProofsOpt.map(p => Base16.encode(p.bytes)).getOrElse("")
    val solution: String = header.equihashSolution.ints.mkString("{", ", ", "}")
    val (minerAddress: String, minerReward: Long) = payload.txs.last.directives.head match {
      case TransferDirective(address, amount, tokenIdOpt) if tokenIdOpt.isEmpty => address -> amount
      case _ => "unknown" -> 0
    }
    val feesTotal: Long = payload.txs.map(_.fee).sum
    val txsSize: Int = payload.txs.map(_.bytes.length).sum

    s"('$encodedId', '$encodedParentId', '${header.version}', '${header.height}', '$proofsRoot'," +
      s" '$stateRoot', '$transactionsRoot', '${header.timestamp}', '${header.difficulty}'," +
      s" '${bytes.length}', '$solution', '$proofs', '${payload.txs.size}', '$minerAddress'," +
      s" '$minerReward', '$feesTotal', '$txsSize', TRUE)"
  }

  def toProtoBlock: BlockProtoMessage = BlockProtoSerializer.toProto(this)
}

object Block {

  val modifierTypeId: ModifierTypeId = ModifierTypeId @@ (100: Byte)

  implicit val jsonEncoder: Encoder[Block] = (b: Block) => Map(
    "header"   -> b.header.asJson,
    "payload"  -> b.payload.asJson,
    "adProofs" -> b.adProofsOpt.map(_.asJson).getOrElse(Map.empty[String, String].asJson)
  ).asJson

  implicit val jsonDecoder: Decoder[Block] = (c: HCursor) => for {
    header  <- c.downField("header").as[Header]
    payload <- c.downField("payload").as[Payload]
  } yield Block(
    header,
    payload,
    None
  )
}

object BlockProtoSerializer {

  def toProto(block: Block): BlockProtoMessage = {
    val initialBlock = BlockProtoMessage()
      .withHeader(block.header.toHeaderProto)
      .withPayload(block.payload.toProtoPayload)
    block.adProofsOpt match {
      case Some(value) => initialBlock.withAdProofsOpt(value.toProtoADProofs)
      case _ => initialBlock
    }
  }

  def fromProto(message: BlockProtoMessage): Try[Block] = Try(Block(
    message.header.map(x => HeaderProtoSerializer.fromProto(x)).get.get,
    message.payload.map(x => PayloadProtoSerializer.fromProto(x)).get.get,
    message.adProofsOpt.map(x => ADProofsProtoSerializer.fromProto(x))
  ))
}

object BlockSerializer extends Serializer[Block] {

  override def toBytes(obj: Block): Array[Byte] = {
    val headerBytes: Array[Byte] = obj.header.serializer.toBytes(obj.header)
    val payloadBytes: Array[Byte] = obj.payload.serializer.toBytes(obj.payload)
    val aDProofsBytes: Array[Byte] = obj.adProofsOpt.map(_.serializer.toBytes(obj.adProofsOpt.get))
      .getOrElse(Array.emptyByteArray)
    Bytes.concat(
      Ints.toByteArray(headerBytes.length),
      headerBytes,
      Ints.toByteArray(payloadBytes.length),
      payloadBytes,
      Ints.toByteArray(aDProofsBytes.length),
      aDProofsBytes
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[Block] = Try {
    var pointer: Int = 4
    val headerSize: Int = Ints.fromByteArray(bytes.slice(0, pointer))
    val header: Try[Header] = HeaderSerializer.parseBytes(bytes.slice(pointer, pointer + headerSize))
    pointer += headerSize
    val payloadSize: Int = Ints.fromByteArray(bytes.slice(pointer, pointer + 4))
    val payload: Try[Payload] =
      PayloadSerializer.parseBytes(bytes.slice(pointer + 4, pointer + 4 + payloadSize))
    pointer += payloadSize + 4
    val aDProofsSize: Int = Ints.fromByteArray(bytes.slice(pointer, pointer + 4))
    val aDProofs: Try[ADProofs] = ADProofSerializer.parseBytes(bytes.slice(pointer + 4, pointer + 4 + aDProofsSize))
    Block(header.get, payload.get, Option(aDProofs.get))
  }
}