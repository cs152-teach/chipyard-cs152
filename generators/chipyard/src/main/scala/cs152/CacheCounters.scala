//**************************************************************************
// CS152 Lab 2 — counter block (MMIO)
//--------------------------------------------------------------------------
//   0x00 cycles   0x04 loads   0x08 stores   0x0c hits
//   0x10 misses   0x14 writebacks            0x18 instret (not implemented, 0)
//   0x1c control  -- write-only, see below
//   0x20 snoop accesses         0x24 snoop hits
//   0x28 magic    -- 0xC5152001; lets software confirm it is talking to this
//                    block at all, so a base-address mismatch between
//                    cs152_counters.h and CS152CounterBase is diagnosed
//                    instead of silently reading nothing
//
//   control (0x1c) write bits:
//     bit 0   zero every counter AND resume counting  (starts a region)
//     bit 1   flush  -- write back dirty lines, then invalidate
//     bit 2   clean  -- write back dirty lines, lines stay resident
//     bit 3   stop counting                          (ends a region)
//
// If bits 0 and 3 are set in the same write, bit 0 wins and counting resumes.

package cs152

import chisel3._
import chisel3.util._
import sodor.common.{MemPortIo, SodorCoreParams}
import sodor.common.Constants._

object CacheCounters {
  /* Also defined as CS152_CTR_MAGIC_VALUE in lab/runtime/cs152_counters.h.
     Change both together. */
  val magic = "hC5152001"
  /* A mystery build (open-ended 1) reports cycles only.*/
  val mysteryMagic = "hC5152002"
}

class CacheCounters(mystery: Boolean = false)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val port  = Flipped(new MemPortIo(data_width = conf.xprlen))
    val stats = Input(new L1DStats)
    val flush = Output(Bool())
    val clean = Output(Bool())
  })

  val cycles     = RegInit(0.U(32.W))
  val loads      = RegInit(0.U(32.W))
  val stores     = RegInit(0.U(32.W))
  val hits       = RegInit(0.U(32.W))
  val misses     = RegInit(0.U(32.W))
  val writebacks = RegInit(0.U(32.W))
  val snoopAcc   = RegInit(0.U(32.W))
  val snoopHits  = RegInit(0.U(32.W))
  val enabled    = RegInit(true.B)

  when (enabled) {
    cycles := cycles + 1.U
    when (io.stats.load)        { loads      := loads      + 1.U }
    when (io.stats.store)       { stores     := stores     + 1.U }
    when (io.stats.hit)         { hits       := hits       + 1.U }
    when (io.stats.miss)        { misses     := misses     + 1.U }
    when (io.stats.writeback)   { writebacks := writebacks + 1.U }
    when (io.stats.snoopAccess) { snoopAcc   := snoopAcc   + 1.U }
    when (io.stats.snoopHit)    { snoopHits  := snoopHits  + 1.U }
  }

  /* Mysterious build: only expose cycles counter */
  def vis(r: UInt): UInt = if (mystery) 0.U(32.W) else r

  val sel = io.port.req.bits.addr(5, 2)
  io.port.req.ready      := true.B
  io.port.resp.valid     := io.port.req.valid
  io.port.resp.bits.data := MuxLookup(sel, 0.U)(Seq(
     0.U -> cycles,
     1.U -> vis(loads),
     2.U -> vis(stores),
     3.U -> vis(hits),
     4.U -> vis(misses),
     5.U -> vis(writebacks),
     6.U -> 0.U,              // instret: would need a core-side retire pulse
     8.U -> vis(snoopAcc),
     9.U -> vis(snoopHits),
    10.U -> (if (mystery) CacheCounters.mysteryMagic.U(32.W)
             else          CacheCounters.magic.U(32.W))
  ))

  val ctrlWrite = io.port.req.valid && io.port.req.bits.fcn === M_XWR && sel === 7.U

  // Bit 3 first, so that bit 0 (below) wins if a write sets both.
  when (ctrlWrite && io.port.req.bits.data(3)) { enabled := false.B }

  when (ctrlWrite && io.port.req.bits.data(0)) {
    cycles := 0.U; loads := 0.U; stores := 0.U
    hits := 0.U; misses := 0.U; writebacks := 0.U
    snoopAcc := 0.U; snoopHits := 0.U
    enabled := true.B
  }

  // NOTE (known issue): zeroing is combinational at the write cycle, but a
  // flush walk runs for many cycles afterwards and its writebacks land in the
  // just-zeroed counter.  Issue FLUSH, wait for it, then ZERO -- as setStats()
  // does -- rather than setting both bits in one write.
  io.flush := ctrlWrite && io.port.req.bits.data(1)
  io.clean := ctrlWrite && io.port.req.bits.data(2)
}
