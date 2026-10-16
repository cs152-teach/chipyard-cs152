//**************************************************************************
// CS152 Lab 4 -- per-core counter block (MMIO)
//--------------------------------------------------------------------------
// Every core has its own block, at the same address: a core reads its own
// L1D's counters, the way it would read its own CSRs.
//
//   0x00 cycles        0x04 loads          0x08 stores
//   0x0c hits          0x10 misses         0x14 writebacks
//   0x18 upgrades      0x1c control        -- write-only (reads 0), see below
//   0x20 invalidations 0x24 interventions  0x28 magic (0xC5152004)
//   0x2c ncores        0x30 bus-wait cycles
//   0x34 load misses   0x38 store misses
//
//   hits           accesses that completed without the bus
//   misses         GetS + GetM this cache issued
//   load misses    the misses a load caused -- under MI a load miss is a GetM
//   store misses   the misses a store caused   (load + store misses = misses)
//   upgrades       Upg: a store to a line this cache held in S
//   writebacks     dirty lines this cache wrote back (eviction or flush)
//   invalidations  valid lines a remote request took away from this cache
//   interventions  dirty lines this cache had to supply to a remote request
//   bus-wait       cycles this cache wanted the bus while another held it
//
//   ncores is a constant, readable before anything is set up; crt.S uses it.
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
  val magic = "hC5152004"
}

class CacheCounters(nCores: Int)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val port  = Flipped(new MemPortIo(data_width = conf.xprlen))
    val stats = Input(new L1DStats)
    val flush = Output(Bool())
    val clean = Output(Bool())
  })

  val enabled = RegInit(true.B)

  // (offset/4, event), per the register map above
  val events = Seq(
     1 -> io.stats.load,
     2 -> io.stats.store,
     3 -> io.stats.hit,
     4 -> io.stats.miss,
     5 -> io.stats.writeback,
     6 -> io.stats.upgrade,
     8 -> io.stats.inval,
     9 -> io.stats.intervention,
    12 -> io.stats.busWait,
    13 -> io.stats.loadMiss,
    14 -> io.stats.storeMiss)

  val cycles   = RegInit(0.U(32.W))
  val counters = events.map { case (_, ev) => RegInit(0.U(32.W)) }

  when (enabled) {
    cycles := cycles + 1.U
    for (((_, ev), c) <- events zip counters) {
      when (ev) { c := c + 1.U }
    }
  }

  val sel = io.port.req.bits.addr(5, 2)
  io.port.req.ready      := true.B
  io.port.resp.valid     := io.port.req.valid
  io.port.resp.bits.data := MuxLookup(sel, 0.U)(
    Seq(0.U -> cycles) ++
    (events zip counters).map { case ((off, _), c) => off.U -> c } ++
    Seq(10.U -> CacheCounters.magic.U(32.W),
        11.U -> nCores.U(32.W)))

  val ctrlWrite = io.port.req.valid && io.port.req.bits.fcn === M_XWR && sel === 7.U

  // Bit 3 first, so that bit 0 (below) wins if a write sets both.
  when (ctrlWrite && io.port.req.bits.data(3)) { enabled := false.B }

  when (ctrlWrite && io.port.req.bits.data(0)) {
    cycles := 0.U
    counters.foreach(_ := 0.U)
    enabled := true.B
  }

  // A flush walk runs for many cycles after the write that starts it, so its
  // writebacks would be counted after a zero in the same write.  Software
  // flushes first, then zeroes (setStats() in lab/runtime/syscalls.c).
  io.flush := ctrlWrite && io.port.req.bits.data(1)
  io.clean := ctrlWrite && io.port.req.bits.data(2)
}
