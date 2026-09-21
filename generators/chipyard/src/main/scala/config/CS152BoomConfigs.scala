//******************************************************************************
// CS152 Lab 3 - BOOM configuration fragments (used by BoomConfigs.scala):
//   WithNCS152BaselineBooms - directed portion (worst-case 1-wide baseline)
//   WithNCS152DefaultBooms  - open-ended 4.1 (branch predictor), 3-wide
//   WithNCS152SmallBooms    - open-ended 4.2 (Spectre), 1-wide
//
// You normally do not need to edit this file; the parameters are here for reference.
//******************************************************************************

package chipyard

import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._

import boom.v3.common._
import boom.v3.exu._

/** Directed portion: worst-case 1-wide BOOM. The four sweep points are the named
  * CS152Baseline* configs in BoomConfigs.scala. */
class WithNCS152BaselineBooms(
    n: Int = 1,
    baselineIntPhysRegisters: Int = 33,
    baselineRobEntries: Int = 4,
    baselineBranchPrediction: Boolean = false,
    baselineRasEntries: Int = 0
  ) extends Config(
  new boom.v3.common.WithTAGELBPD ++ // BPD hardware; only used when enableBranchPrediction=true
  new Config((site, here, up) => {
    case TilesLocated(InSubsystem) => {
      val prev = up(TilesLocated(InSubsystem), site)
      val idOffset = up(NumTiles)
      (0 until n).map { i =>
        val coreWidth = 1
        val memWidth = 1
        BoomTileAttachParams(
          tileParams = BoomTileParams(
            core = BoomCoreParams(
              fetchWidth = 4,
              numRobEntries = baselineRobEntries,
              numIntPhysRegisters = baselineIntPhysRegisters,
              numLdqEntries = 8,
              numStqEntries = 8,
              maxBrCount = 8,
              enableBranchPrediction = baselineBranchPrediction,
              numRasEntries = baselineRasEntries,

              // DO NOT CHANGE BELOW
              enableBranchPrintf = true,
              decodeWidth = coreWidth,
              numFetchBufferEntries = coreWidth * 8,
              numDCacheBanks = memWidth,
              issueParams = Seq(
                IssueParams(issueWidth=memWidth,  numEntries=8,  iqType=IQT_MEM.litValue, dispatchWidth=coreWidth),
                IssueParams(issueWidth=coreWidth, numEntries=32, iqType=IQT_INT.litValue, dispatchWidth=coreWidth),
                IssueParams(issueWidth=1,         numEntries=4,  iqType=IQT_FP.litValue , dispatchWidth=coreWidth))
                // DO NOT CHANGE ABOVE
            ),
            dcache = Some(DCacheParams(
              rowBits=64, nSets=64, nWays=4, nMSHRs=2
            )),
            icache = Some(ICacheParams(
              rowBits=64, nSets=64, nWays=8, fetchBytes=2*4
            )),
            tileId = i + idOffset
          ),
          crossingParams = RocketCrossingParams()
        )
      } ++ prev
    }
    case NumTiles => up(NumTiles) + n
  })
)

/** Open-ended 4.1 (branch predictor): 3-wide BOOM. Matches the Problem 4.1 column of
  * the open-ended configuration table. */
class WithNCS152DefaultBooms(n: Int = 1) extends Config(
  new boom.v3.common.WithTAGELBPD ++ // Default to TAGE-L BPD (overridden by the config's BPD mixin)
  new Config((site, here, up) => {
    case TilesLocated(InSubsystem) => {
      val prev = up(TilesLocated(InSubsystem), site)
      val idOffset = up(NumTiles)
      (0 until n).map { i =>
        val coreWidth = 3
        val memWidth = 1
        val nIssueSlots = 32
        BoomTileAttachParams(
          tileParams = BoomTileParams(
            core = BoomCoreParams(
              fetchWidth = 4,
              numRobEntries = 96,
              numIntPhysRegisters = 96,
              numLdqEntries = 16,
              numStqEntries = 16,
              maxBrCount = 12,
              enableBranchPrediction = true,
              numRasEntries = 16,

              // DO NOT CHANGE BELOW
              enableBranchPrintf = true,
              decodeWidth = coreWidth,
              numFetchBufferEntries = coreWidth * 8,
              numDCacheBanks = memWidth,
              issueParams = Seq(
                IssueParams(issueWidth=memWidth,  numEntries=nIssueSlots, iqType=IQT_MEM.litValue, dispatchWidth=coreWidth),
                IssueParams(issueWidth=coreWidth, numEntries=nIssueSlots, iqType=IQT_INT.litValue, dispatchWidth=coreWidth),
                IssueParams(issueWidth=1,         numEntries=nIssueSlots, iqType=IQT_FP.litValue , dispatchWidth=coreWidth))
                // DO NOT CHANGE ABOVE
            ),
            dcache = Some(DCacheParams(
              rowBits=64, nSets=64, nWays=4, nMSHRs=2
            )),
            icache = Some(ICacheParams(
              rowBits=64, nSets=64, nWays=8, fetchBytes=2*4
            )),
            tileId = i + idOffset
          ),
          crossingParams = RocketCrossingParams()
        )
      } ++ prev
    }
    case NumTiles => up(NumTiles) + n
  })
)

/** Open-ended 4.2 (Spectre): 1-wide BOOM. Matches the Problem 4.2 column of the
  * open-ended configuration table. */
class WithNCS152SmallBooms(n: Int = 1) extends Config(
  new boom.v3.common.WithTAGELBPD ++ // BPD hardware (config composes its own BPD mixin)
  new Config((site, here, up) => {
    case TilesLocated(InSubsystem) => {
      val prev = up(TilesLocated(InSubsystem), site)
      val idOffset = up(NumTiles)
      (0 until n).map { i =>
        val coreWidth = 1
        val memWidth = 1
        val nIssueSlots = 8
        BoomTileAttachParams(
          tileParams = BoomTileParams(
            core = BoomCoreParams(
              fetchWidth = 4,
              numRobEntries = 32,
              numIntPhysRegisters = 52,
              numFpPhysRegisters = 48,
              numLdqEntries = 8,
              numStqEntries = 8,
              maxBrCount = 8,
              enableBranchPrediction = true,
              numRasEntries = 16,

              // DO NOT CHANGE BELOW
              enableBranchPrintf = true,
              decodeWidth = coreWidth,
              numFetchBufferEntries = coreWidth * 8,
              numDCacheBanks = memWidth,
              issueParams = Seq(
                IssueParams(issueWidth=memWidth,  numEntries=nIssueSlots, iqType=IQT_MEM.litValue, dispatchWidth=coreWidth),
                IssueParams(issueWidth=coreWidth, numEntries=nIssueSlots, iqType=IQT_INT.litValue, dispatchWidth=coreWidth),
                IssueParams(issueWidth=1,         numEntries=nIssueSlots, iqType=IQT_FP.litValue , dispatchWidth=coreWidth))
                // DO NOT CHANGE ABOVE
            ),
            dcache = Some(DCacheParams(
              rowBits=64, nSets=64, nWays=4, nMSHRs=2
            )),
            icache = Some(ICacheParams(
              rowBits=64, nSets=64, nWays=8, fetchBytes=2*4
            )),
            tileId = i + idOffset
          ),
          crossingParams = RocketCrossingParams()
        )
      } ++ prev
    }
    case NumTiles => up(NumTiles) + n
  })
)
