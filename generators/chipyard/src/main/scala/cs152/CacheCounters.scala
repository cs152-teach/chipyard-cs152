//**************************************************************************
// CS152 Lab 2 — counter block (MMIO)
//--------------------------------------------------------------------------
//   0x00 cycles   0x04 loads   0x08 stores   0x0c hits
//   0x10 misses   0x14 writebacks            0x18 instret (not implemented, 0)
//   0x20 snoop accesses         0x24 snoop hits
//   0x1c control  -- bit 0 zeroes every counter, bit 1 flushes (writeback +
//                    invalidate), bit 2 cleans (writeback, lines stay resident)

package cs152

import chisel3._
import chisel3.util._
import sodor.common.{MemPortIo, SodorCoreParams}
import sodor.common.Constants._

class CacheCounters(implicit conf: SodorCoreParams) extends Module {
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

  cycles := cycles + 1.U
  when (io.stats.load)      { loads      := loads      + 1.U }
  when (io.stats.store)     { stores     := stores     + 1.U }
  when (io.stats.hit)       { hits       := hits       + 1.U }
  when (io.stats.miss)      { misses     := misses     + 1.U }
  when (io.stats.writeback)   { writebacks := writebacks + 1.U }
  when (io.stats.snoopAccess) { snoopAcc   := snoopAcc   + 1.U }
  when (io.stats.snoopHit)    { snoopHits  := snoopHits  + 1.U }

  val sel = io.port.req.bits.addr(5, 2)
  io.port.req.ready      := true.B
  io.port.resp.valid     := io.port.req.valid
  io.port.resp.bits.data := MuxLookup(sel, 0.U)(Seq(
    0.U -> cycles,
    1.U -> loads,
    2.U -> stores,
    3.U -> hits,
    4.U -> misses,
    5.U -> writebacks,
    6.U -> 0.U,              // instret: would need a core-side retire pulse
    8.U -> snoopAcc,
    9.U -> snoopHits
  ))

  // Control word (0x1c):  bit 0 = zero the counters,  bit 1 = flush the cache.
  val ctrlWrite = io.port.req.valid && io.port.req.bits.fcn === M_XWR && sel === 7.U
  when (ctrlWrite && io.port.req.bits.data(0)) {
    cycles := 0.U; loads := 0.U; stores := 0.U
    hits := 0.U; misses := 0.U; writebacks := 0.U
    snoopAcc := 0.U; snoopHits := 0.U
  }
  io.flush := ctrlWrite && io.port.req.bits.data(1)
  io.clean := ctrlWrite && io.port.req.bits.data(2)
}
