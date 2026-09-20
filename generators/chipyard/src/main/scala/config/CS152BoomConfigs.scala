//******************************************************************************
// CS152 Lab 3 - superproject-hoisted BOOM config fragment.
//
// Upstream riscv-boom already ships WithNCS152BaselineBooms and
// WithNCS152DefaultBooms (in boom.v3.common), which back the directed portion and
// the branch-predictor open-ended problem. The Spectre open-ended problem needs a
// small 1-wide BOOM (Problem 4.2 in the lab document) that upstream does not carry,
// so we define it here in the superproject rather than forking the boom submodule.
// Keeping it out of the submodule lets fa26-lab3 track upstream boom by pointer, so
// merges from `main` stay fast-forward-clean.
//
// The dispatch-trace debug aid (enableDispatchPrintf), which is a genuine BOOM RTL
// change and cannot be hoisted, is provided separately by
// patches/boom-dispatch-printf.patch (applied by build-setup.sh). To trace dispatched
// instructions for the Spectre problem, apply that patch (setup does this) and add
// `enableDispatchPrintf = true` to the BoomCoreParams below, then rebuild.
//******************************************************************************

package chipyard

import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._

import boom.v3.common._
import boom.v3.exu._

/**
 * 1-wide out-of-order BOOM used for the Spectre open-ended problem (Problem 4.2).
 * Mirrors boom.v3.common.WithNCS152DefaultBooms in structure; the parameters match
 * the "Problem 4.2" column of the open-ended configuration table in the lab document.
 */
class WithNCS152SmallBooms(n: Int = 1) extends Config(
  new boom.v3.common.WithTAGELBPD ++ // Default to TAGE-L BPD (overridden by the config's own BPD mixin)
  new Config((site, here, up) => {
    case TilesLocated(InSubsystem) => {
      val prev = up(TilesLocated(InSubsystem), site)
      val idOffset = up(NumTiles)
      (0 until n).map { i =>
        val coreWidth = 1                     // CS152: Change me (1 to 4)
        val memWidth = 1                      // CS152: Change me (1 or 2)
        val nIssueSlots = 8                   // CS152: Change me (2+)
        BoomTileAttachParams(
          tileParams = BoomTileParams(
            core = BoomCoreParams(
              fetchWidth = 4,                   // CS152: Change me (4 or 8)
              numRobEntries = 32,               // CS152: Change me (2+)
              numIntPhysRegisters = 52,         // CS152: Change me (33+)
              numFpPhysRegisters = 48,          // CS152: Change me (33+)
              numLdqEntries = 8,                // CS152: Change me (2+)
              numStqEntries = 8,                // CS152: Change me (2+)
              maxBrCount = 8,                   // CS152: Change me (2+)
              enableBranchPrediction = true,    // CS152: Change me
              numRasEntries = 16,               // CS152: Change me

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
              rowBits=64,
              nSets=64, // CS152: Change me (must be pow2, 2-64)
              nWays=4,  // CS152: Change me (1-8)
              nMSHRs=2  // CS152: Change me (1+)
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
