//**************************************************************************
//   host read  hit  -> serve the cache's copy (newer than memory)
//   host write hit  -> the line absorbs the write; memory is written too, so
//                      cache and memory agree for that word and `dirty` is
//                      unchanged
//   miss (either)   -> the storage array answers, exactly as before
//--------------------------------------------------------------------------

package cs152

import chisel3._
import chisel3.util._
import sodor.common.{MemPortIo, SodorCoreParams}
import sodor.common.Constants._

class HostSnoopPort(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val host  = Flipped(new MemPortIo(data_width = 32))  // from SodorScratchpadAdapter
    val mem   = new MemPortIo(data_width = 32)           // to the scratchpad debug port
    val snoop = Flipped(new L1DSnoopIO)                  // into the L1D
  })

  // Always forward to the storage array: a miss must still be served, and on a
  // write hit writing both keeps that word consistent in cache and memory.
  io.mem.req.valid  := io.host.req.valid
  io.mem.req.bits   := io.host.req.bits
  io.host.req.ready := io.mem.req.ready

  io.snoop.valid := io.host.req.valid
  io.snoop.addr  := io.host.req.bits.addr
  io.snoop.write := io.host.req.bits.fcn === M_XWR
  io.snoop.wdata := io.host.req.bits.data
  io.snoop.typ   := io.host.req.bits.typ

  // On a read hit the cache is the authority; otherwise memory is.
  io.host.resp.valid     := io.mem.resp.valid
  io.host.resp.bits.data := Mux(io.snoop.hit, io.snoop.rdata, io.mem.resp.bits.data)
}
