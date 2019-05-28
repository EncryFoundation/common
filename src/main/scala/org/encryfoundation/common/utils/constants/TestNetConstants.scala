package org.encryfoundation.common.utils.constants

import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{ADKey, Difficulty, Height}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

object TestNetConstants extends Constants {

  val DigestLength: Int = 32

  val ModifierIdSize: Int = DigestLength

  val PayloadMaxSize: Int = 1000000

  val HeaderMaxSize: Int = 200

  val DefaultKeepVersions: Int = 200

  val PersistentByteCost: Int = 16

  val StateByteCost: Int = 26

  val MaxDataLength: Int = 1000

  val AfterGenesisStateDigestHex: String = "39bc0df81fec1ee7b3804eb42084883ce4925aec8393df39806f00e129ade9f101"

  val GenesisStateVersion: String = "0909090909090909090909090909090909090909090909090909090909090909"

  val IntrinsicTokenId: ADKey = ADKey !@@ Algos.hash("intrinsic_token")

  object TestNetChainConstants extends ChainConstants {

    val ConsensusScheme: String = "equihash"

    val HashLength: Int = 32

    val MaxTarget: BigInt = BigInt(1, Array.fill(HashLength)((-1).toByte))

    val InitialDifficulty: Difficulty = Difficulty @@ BigInt(1)

    val Version: Byte = 0: Byte

    val InitialEmissionAmount: Int = 1000000000

    val EmissionDecay = 0.05

    val EmissionEpochLength: Int = 5040

    val DesiredBlockInterval: FiniteDuration = 120.seconds

    val NewHeaderTimeMultiplier: Int = 5

    val RetargetingEpochsQty: Int = 4

    val EpochLength: Int = 100

    val GenesisHeight: Height = Height @@ 0

    val PreGenesisHeight: Height = Height @@ (GenesisHeight - 1)

    val MaxRollbackDepth: Int = 100

    val MaxTimeDrift: Long = 2.hours.toMillis
  }

  object TestNetEquihash extends EquiHashConstants {

    val n: Char = 96

    val k: Char = 5
  }
}