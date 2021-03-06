package org.encryfoundation.common.modifiers.mempool.transaction

import com.google.common.primitives.{ Ints, Shorts }
import io.circe.syntax._
import io.circe.{ Decoder, Encoder, HCursor }
import org.encryfoundation.common.serialization.{ BytesSerializable, SerializationException, Serializer }
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.prismlang.compiler.{ CompiledContract, CompiledContractSerializer }
import org.encryfoundation.common.utils.TaggedTypes.ADKey
import org.encryfoundation.common.utils.constants.TestNetConstants
import scala.util.Try

case class Input(boxId: ADKey, contract: Either[CompiledContract, RegularContract], proofs: List[Proof])
    extends BytesSerializable {

  override type M = Input

  override def serializer: Serializer[M] = InputSerializer

  lazy val bytesWithoutProof: Array[Byte] = InputSerializer.toBytesWithoutProof(this)

  def isUnsigned: Boolean = proofs.isEmpty

  def realContract: CompiledContract = contract.fold(identity, _.contract)
}

object Input {

  def unsigned(boxId: ADKey, contract: Either[CompiledContract, RegularContract]): Input =
    Input(boxId, contract, List.empty)

  implicit val jsonEncoder: Encoder[Input] = (u: Input) =>
    Map(
      "boxId"    -> Algos.encode(u.boxId).asJson,
      "contract" -> Algos.encode(InputSerializer.encodeEitherCompiledOrRegular(u.contract)).asJson,
      "proofs"   -> u.proofs.map(_.asJson).asJson
    ).asJson

  implicit val jsonDecoder: Decoder[Input] = (c: HCursor) =>
    for {
      boxId         <- c.downField("boxId").as[String]
      contractBytes <- c.downField("contract").as[String]
      proofs        <- c.downField("proofs").as[List[Proof]]
    } yield
      Algos
        .decode(contractBytes)
        .flatMap(InputSerializer.decodeEitherCompiledOrRegular)
        .flatMap(contract => Algos.decode(boxId).map(id => Input(ADKey @@ id, contract, proofs)))
        .getOrElse(throw new Exception("Decoding failed"))
}

object InputSerializer extends Serializer[Input] {

  private val CCTypeId: Byte = 98
  private val RCTypeId: Byte = 99

  def encodeEitherCompiledOrRegular(contract: Either[CompiledContract, RegularContract]): Array[Byte] =
    contract.fold(CCTypeId +: _.bytes, RCTypeId +: _.bytes)

  def decodeEitherCompiledOrRegular(bytes: Array[Byte]): Try[Either[CompiledContract, RegularContract]] =
    bytes.head match {
      case CCTypeId => CompiledContractSerializer.parseBytes(bytes.tail).map(Left.apply)
      case RCTypeId => RegularContract.Serializer.parseBytes(bytes.tail).map(Right.apply)
    }

  def toBytesWithoutProof(obj: Input): Array[Byte] = {
    val contractBytes: Array[Byte] = encodeEitherCompiledOrRegular(obj.contract)
    obj.boxId ++ Ints.toByteArray(contractBytes.length) ++ contractBytes
  }

  override def toBytes(obj: Input): Array[Byte] =
    if (obj.isUnsigned) toBytesWithoutProof(obj)
    else {
      val proofsBytes: Array[Byte] = obj.proofs.foldLeft(Array.empty[Byte]) {
        case (acc, proof) =>
          val proofBytes: Array[Byte] = ProofSerializer.toBytes(proof)
          acc ++ Shorts.toByteArray(proofBytes.length.toShort) ++ proofBytes
      }
      toBytesWithoutProof(obj) ++ Array(obj.proofs.size.toByte) ++ proofsBytes
    }

  override def parseBytes(bytes: Array[Byte]): Try[Input] =
    Try {
      val boxId: ADKey = ADKey @@ bytes.take(TestNetConstants.ModifierIdSize)
      val contractLen: Int =
        Ints.fromByteArray(bytes.slice(TestNetConstants.ModifierIdSize, TestNetConstants.ModifierIdSize + 4))
      boxId -> contractLen
    }.flatMap {
      case (boxId, contractLen) =>
        decodeEitherCompiledOrRegular(
          bytes.slice(TestNetConstants.ModifierIdSize + 4, TestNetConstants.ModifierIdSize + 4 + contractLen)
        ).map { contract =>
          val proofsQty: Int =
            if (bytes.length <= TestNetConstants.ModifierIdSize + 4 + contractLen) 0
            else bytes.drop(TestNetConstants.ModifierIdSize + 4 + contractLen).head
          val (proofs: List[Proof], _) =
            (0 until proofsQty).foldLeft(List.empty[Proof],
                                         bytes.drop(TestNetConstants.ModifierIdSize + 5 + contractLen)) {
              case ((acc, bytesAcc), _) =>
                val proofLen: Int = Shorts.fromByteArray(bytesAcc.take(2))
                val proof: Proof =
                  ProofSerializer.parseBytes(bytesAcc.slice(2, proofLen + 2)).getOrElse(throw SerializationException)
                (acc :+ proof) -> bytesAcc.drop(proofLen + 2)
            }
          Input(boxId, contract, proofs)
        }
    }
}
